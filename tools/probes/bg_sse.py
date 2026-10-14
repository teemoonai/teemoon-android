#!/usr/bin/env python3
"""A slow SSE server that logs, per chunk, whether the CLIENT is still reading.

    python3 tools/probes/bg_sse.py 8780 400 0.5        # port, chunks, seconds between

THE QUESTION IT ANSWERS: does a cloud turn's SOCKET survive the app being
backgrounded? The foreground service (TurnService) keeps the PROCESS alive —
measured, `oom_score_adj` 50 with it against 900 without.

ANSWERED ON THE PIXEL 10 PRO, 2026-09-22, against the real cloud rather than
this server: a near.ai glm-5.3-flash E2EE turn, HOME pressed 6 s after send,
ran 304.3 s entirely backgrounded (ttft 229.6 s — nearly four minutes of
reasoning with no content on the wire — then 74.7 s of generation, 11282
tokens), HTTP 200, reply whole, result notification posted. Watched from
`/proc/net/tcp*` by UID and `/proc/<pid>/stat`: the process never died, CPU
kept climbing (~12 ticks/s), rx queues stayed at 0 (the app kept draining).
`oom_score_adj` went 0 → 50 at HOME, 50 → 200 about a minute later (the
recent-foreground boost expiring; 200 is a steady FGS), 200 → 700 when the
service stopped. This harness is still the way to run the AVD control.

HOW TO RUN IT. Start this, then seed the app at the Mac through the emulator's
host alias and send a turn:

    ARM=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')   # arms the launch that names it, within a minute
    adb -s emulator-5554 shell "run-as ai.teemoon sh -c 'echo $ARM > files/uitest-armed'"   # (UiTestSeeding.consumeArm)
    adb -s emulator-5554 shell am start -n ai.teemoon/.app.MainActivity \
        --ez uitesting true --es uitest_arm $ARM --es seed_local_endpoint "http://10.0.2.2:8780/v1" \
        --es seed_local_model "bg-probe"

then type a prompt, send, press HOME, and read this server's log. USE THE AVD:
before TurnService existed it killed the process about 8 s after HOME (client
gone after 27 chunks), which makes it the harsh case and a real control. A
Pixel 10 Pro survived five minutes backgrounded with no service at all, so it
proves much less.

The question: does a backgrounded Android app keep consuming a cloud stream?
Answered from the server side on purpose — the app's own logs stop being
trustworthy the moment the thing under test is "did that process survive".

Each chunk is written and flushed with a timestamp. When the client goes away the
socket write fails or blocks, and that timestamp is the answer.
"""
import http.server, json, socketserver, sys, threading, time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8770
CHUNKS = int(sys.argv[2]) if len(sys.argv) > 2 else 240      # ~2 minutes at 0.5s
DELAY = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5
T0 = time.time()


def log(msg):
    print(f"[{time.time() - T0:6.1f}s] {msg}", flush=True)


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if "/models" in self.path:
            body = json.dumps({"data": [{"id": "bg-probe"}]}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(n)
        log(f"REQUEST {self.path} — streaming {CHUNKS} chunks at {DELAY}s")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        served = 0
        try:
            for i in range(CHUNKS):
                piece = {"choices": [{"delta": {"content": f"chunk{i} "}}]}
                payload = f"data: {json.dumps(piece)}\n\n".encode()
                self.wfile.write(hex(len(payload))[2:].encode() + b"\r\n" + payload + b"\r\n")
                self.wfile.flush()
                served = i + 1
                if i % 10 == 0:
                    log(f"served chunk {i}")
                time.sleep(DELAY)
            self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
            log(f"STREAM COMPLETED — {served} chunks, client read every one")
        except Exception as e:
            log(f"CLIENT GONE after {served} chunks: {type(e).__name__}: {e}")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


log(f"listening on {PORT}")
Server(("0.0.0.0", PORT), H).serve_forever()
