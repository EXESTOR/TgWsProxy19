"""Android-обёртка над консольным tg-ws-proxy (Flowseal/tg-ws-proxy).

Запускает asyncio-цикл прокси в отдельном потоке и даёт Kotlin-слою
управлять им: start_proxy() / stop_proxy() / get_logs() / is_running().

Ключевые отличия от CLI-запуска:
  * proxy_config заполняется напрямую, без argparse и без sys.exit();
  * собственный event loop в фоновом потоке;
  * signal.signal()/set_wakeup_fd() заворачиваются в безопасные обёртки,
    потому что на Android (да и в любом неглавном потоке) они бросают
    ValueError: signal only works in main thread of the main interpreter.
"""
from __future__ import annotations

import asyncio
import collections
import logging
import signal
import sys
import threading
import traceback

_LOCK = threading.RLock()
_LOGS = collections.deque(maxlen=400)
_LOG_FORMAT = logging.Formatter(
    "%(asctime)s  %(levelname)-5s  %(message)s", datefmt="%H:%M:%S"
)
_handler = None
_console = None

_loop = None
_stop_event = None
_thread = None
_running = False
_last_error = ""

_original_signal = signal.signal
_original_wakeup_fd = signal.set_wakeup_fd
_signals_patched = False


class _RingHandler(logging.Handler):
    """Копит строки лога для UI, не мешая обычному выводу в logcat."""

    def emit(self, record):
        try:
            message = self.format(record)
        except Exception:
            message = record.getMessage()
        with _LOCK:
            _LOGS.append(message)


def _install_logging():
    global _handler, _console
    with _LOCK:
        root = logging.getLogger()
        if _handler is None:
            _handler = _RingHandler()
            _handler.setFormatter(_LOG_FORMAT)
            root.addHandler(_handler)
        if _console is None:
            # sys.stdout у Chaquopy перенаправлен в logcat (тег python.stdout)
            _console = logging.StreamHandler(sys.stdout)
            _console.setFormatter(_LOG_FORMAT)
            root.addHandler(_console)
        root = logging.getLogger()
        if root.level > logging.INFO or root.level == logging.NOTSET:
            root.setLevel(logging.INFO)
    for noisy in ("asyncio", "httpx", "httpcore", "h2", "hpack"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def _patch_signals():
    """Не даёт прокси упасть на signal.signal() в фоновом потоке."""
    global _signals_patched
    if _signals_patched:
        return

    def _safe_signal(sig, handler):
        if threading.current_thread() is not threading.main_thread():
            return None
        return _original_signal(sig, handler)

    def _safe_wakeup_fd(fd, *args, **kwargs):
        if threading.current_thread() is not threading.main_thread():
            return -1
        return _original_wakeup_fd(fd, *args, **kwargs)

    try:
        signal.signal = _safe_signal
        signal.set_wakeup_fd = _safe_wakeup_fd
        _signals_patched = True
    except Exception:
        pass


def _parse_dc_entries(raw):
    text = str(raw or "").replace(",", " ").replace(";", " ")
    return [item for item in text.split() if item]


def start_proxy(port=1443, secret="", no_h2=True, dc_ip="4:149.154.167.220"):
    """Запускает прокси. Возвращает "" при успехе или текст ошибки."""
    global _thread, _running, _last_error

    with _LOCK:
        if _running or (_thread is not None and _thread.is_alive()):
            return "Прокси уже запущен"

    _install_logging()
    _patch_signals()

    try:
        from proxy.config import proxy_config, parse_dc_ip_list
    except Exception:
        detail = traceback.format_exc()
        return "Не удалось импортировать proxy: " + detail.strip().splitlines()[-1]

    try:
        listen_port = int(port)
    except (TypeError, ValueError):
        return "Некорректный порт: %r" % (port,)
    if not 1 <= listen_port <= 65535:
        return "Порт вне диапазона 1..65535: %d" % listen_port

    secret = str(secret or "").strip().lower()
    if len(secret) != 32:
        return "Secret должен быть 32 hex-символа"
    try:
        bytes.fromhex(secret)
    except ValueError:
        return "Secret должен быть валидным hex"

    try:
        dc_redirects = parse_dc_ip_list(_parse_dc_entries(dc_ip))
    except ValueError as exc:
        return "Ошибка --dc-ip: %s" % exc

    proxy_config.host = "127.0.0.1"
    proxy_config.port = listen_port
    proxy_config.secret = secret
    proxy_config.dc_redirects = dc_redirects
    proxy_config.buffer_size = 256 * 1024
    proxy_config.pool_size = 4
    proxy_config.fallback_cfproxy = True
    proxy_config.cfproxy_user_domains = []
    proxy_config.cfproxy_worker_domains = []
    proxy_config.cfproxy_h2_media = not bool(no_h2)
    proxy_config.disable_secure = False
    proxy_config.fake_tls_domain = ""
    proxy_config.proxy_protocol = False
    proxy_config.force_test_dc = False

    worker = threading.Thread(target=_run_thread, name="tg-ws-proxy", daemon=True)
    with _LOCK:
        _running = True
        _last_error = ""
        _thread = worker
        _LOGS.append(
            ">>> запуск прокси: 127.0.0.1:%d, h2=%s, dc=%s"
            % (listen_port, "off" if no_h2 else "on", dc_redirects or "default")
        )
    print("[runner] start_proxy: 127.0.0.1:%d h2=%s" % (listen_port, "off" if no_h2 else "on"), flush=True)
    worker.start()
    return ""


def _run_thread():
    global _loop, _stop_event, _running, _last_error, _thread

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    stop_event = asyncio.Event()
    with _LOCK:
        _loop = loop
        _stop_event = stop_event

    try:
        from proxy.tg_ws_proxy import _run

        loop.run_until_complete(_run(stop_event))
    except BaseException:
        detail = traceback.format_exc()
        with _LOCK:
            _last_error = detail
            _LOGS.append("!!! Критическая ошибка:\n" + detail)
    finally:
        try:
            pending = [task for task in asyncio.all_tasks(loop) if not task.done()]
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(
                    asyncio.gather(*pending, return_exceptions=True)
                )
            loop.run_until_complete(loop.shutdown_asyncgens())
        except BaseException:
            pass
        try:
            loop.close()
        except BaseException:
            pass
        with _LOCK:
            _loop = None
            _stop_event = None
            _running = False
            _thread = None
            _LOGS.append(">>> прокси остановлен")


def stop_proxy():
    """Останавливает цикл и ждёт завершения потока. "" при успехе."""
    with _LOCK:
        loop = _loop
        stop_event = _stop_event
        worker = _thread

    if loop is None or stop_event is None:
        return "Прокси не запущен"

    try:
        loop.call_soon_threadsafe(stop_event.set)
    except RuntimeError:
        pass

    if worker is not None and worker is not threading.current_thread():
        worker.join(8.0)
    print("[runner] stop_proxy finished", flush=True)
    return ""


def is_running():
    with _LOCK:
        return bool(_running)


def get_last_error():
    with _LOCK:
        return _last_error


def clear_logs():
    with _LOCK:
        _LOGS.clear()
    return ""


def get_stats():
    """Счётчики прокси в JSON для UI."""
    try:
        from proxy.stats import stats
        data = {
            "total": int(stats.connections_total),
            "active": int(stats.connections_active),
            "ws": int(stats.connections_ws),
            "cf": int(stats.connections_cfproxy),
            "bad": int(stats.connections_bad),
            "up": int(stats.bytes_up),
            "down": int(stats.bytes_down),
        }
        import json
        return json.dumps(data)
    except Exception:
        return "{}"


def get_logs(max_lines=300):
    try:
        limit = max(1, int(max_lines))
    except (TypeError, ValueError):
        limit = 300
    with _LOCK:
        return "\n".join(list(_LOGS)[-limit:])
