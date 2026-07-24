FROM python:3.11-slim

WORKDIR /app

COPY webhook-server.py .

EXPOSE 8082

CMD ["python", "-u", "webhook-server.py"]