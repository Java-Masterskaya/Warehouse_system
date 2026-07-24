from http.server import HTTPServer, BaseHTTPRequestHandler
import json

class WebhookHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)
        
        print(f"\n{'='*60}")
        print(f"WEBHOOK ALERT RECEIVED")
        print(f"{'='*60}")
        print(f"Headers: {self.headers}")
        print(f"Body: {post_data.decode('utf-8')}")
        print(f"{'='*60}\n")
        
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({"status": "ok"}).encode())

if __name__ == '__main__':
    server = HTTPServer(('0.0.0.0', 8082), WebhookHandler)
    print("Webhook server started on port 8082...")
    server.serve_forever()