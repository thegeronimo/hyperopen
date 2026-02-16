import { sleep } from "./util.mjs";

export class CDPClient {
  constructor(webSocketUrl) {
    this.webSocketUrl = webSocketUrl;
    this.socket = null;
    this.connected = false;
    this.idCounter = 0;
    this.pending = new Map();
    this.listeners = new Map();
  }

  async connect() {
    if (this.connected && this.socket?.readyState === WebSocket.OPEN) {
      return;
    }

    this.socket = new WebSocket(this.webSocketUrl);

    await new Promise((resolve, reject) => {
      const onOpen = () => {
        this.connected = true;
        cleanup();
        resolve();
      };
      const onError = (event) => {
        cleanup();
        reject(new Error(`CDP websocket connection failed: ${event?.message || "unknown"}`));
      };
      const onClose = () => {
        cleanup();
        reject(new Error("CDP websocket closed before opening"));
      };
      const cleanup = () => {
        this.socket.removeEventListener("open", onOpen);
        this.socket.removeEventListener("error", onError);
        this.socket.removeEventListener("close", onClose);
      };
      this.socket.addEventListener("open", onOpen);
      this.socket.addEventListener("error", onError);
      this.socket.addEventListener("close", onClose);
    });

    this.socket.addEventListener("message", (event) => {
      this.onMessage(event.data);
    });

    this.socket.addEventListener("close", () => {
      this.connected = false;
      const pending = [...this.pending.values()];
      this.pending.clear();
      for (const item of pending) {
        item.reject(new Error("CDP socket closed"));
      }
    });

    this.socket.addEventListener("error", () => {
      // on close handler rejects pending requests
    });
  }

  onMessage(raw) {
    let msg;
    try {
      msg = JSON.parse(raw);
    } catch (_err) {
      return;
    }

    if (msg.id && this.pending.has(msg.id)) {
      const pending = this.pending.get(msg.id);
      this.pending.delete(msg.id);
      if (msg.error) {
        pending.reject(new Error(`${pending.method} failed: ${JSON.stringify(msg.error)}`));
      } else {
        pending.resolve(msg.result || {});
      }
      return;
    }

    if (msg.method) {
      const listeners = this.listeners.get(msg.method);
      if (listeners) {
        for (const handler of listeners) {
          try {
            handler(msg);
          } catch (_err) {
            // swallow listener errors
          }
        }
      }

      const anyListeners = this.listeners.get("*");
      if (anyListeners) {
        for (const handler of anyListeners) {
          try {
            handler(msg);
          } catch (_err) {
            // swallow listener errors
          }
        }
      }
    }
  }

  on(method, handler) {
    if (!this.listeners.has(method)) {
      this.listeners.set(method, new Set());
    }
    this.listeners.get(method).add(handler);
    return () => {
      this.listeners.get(method)?.delete(handler);
    };
  }

  async waitForEvent(method, options = {}) {
    const { timeoutMs = 15000, sessionId, predicate } = options;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        unsubscribe();
        reject(new Error(`Timed out waiting for event ${method}`));
      }, timeoutMs);

      const unsubscribe = this.on(method, (msg) => {
        if (sessionId && msg.sessionId !== sessionId) {
          return;
        }
        if (predicate && !predicate(msg)) {
          return;
        }
        clearTimeout(timer);
        unsubscribe();
        resolve(msg);
      });
    });
  }

  async send(method, params = {}, sessionId = undefined, timeoutMs = 15000) {
    if (!this.connected || !this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("CDP client is not connected");
    }

    const id = ++this.idCounter;
    const payload = { id, method, params };
    if (sessionId) {
      payload.sessionId = sessionId;
    }

    this.socket.send(JSON.stringify(payload));

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Timed out waiting for ${method}`));
      }, timeoutMs);

      this.pending.set(id, {
        method,
        resolve: (result) => {
          clearTimeout(timeout);
          resolve(result);
        },
        reject: (error) => {
          clearTimeout(timeout);
          reject(error);
        }
      });
    });
  }

  async close() {
    if (!this.socket) {
      return;
    }
    const socket = this.socket;
    this.socket = null;

    if (socket.readyState === WebSocket.CLOSED) {
      return;
    }

    socket.close();

    const deadline = Date.now() + 2000;
    while (Date.now() < deadline && socket.readyState !== WebSocket.CLOSED) {
      await sleep(25);
    }
  }
}

export async function getBrowserWsUrl(port) {
  const response = await fetch(`http://127.0.0.1:${port}/json/version`);
  if (!response.ok) {
    throw new Error(`Failed to fetch browser version for port ${port}: ${response.status}`);
  }
  const payload = await response.json();
  if (!payload.webSocketDebuggerUrl) {
    throw new Error(`No webSocketDebuggerUrl returned for port ${port}`);
  }
  return payload.webSocketDebuggerUrl;
}
