FROM python:3.11-slim
WORKDIR /app
COPY apps/__init__.py /app/apps/__init__.py
COPY apps/cloud /app/apps/cloud
ENV PYTHONUNBUFFERED=1
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/healthz', timeout=3).read()"
CMD ["python", "-m", "apps.cloud.server", "--host", "0.0.0.0", "--port", "8787", "--db", "/data/cloud.sqlite"]
