# 레포 루트에서 빌드: docker build -f engine-server/Dockerfile .
# 엔진 파일은 백엔드에 있는 것을 그대로 써서 복사본이 늘지 않게 한다
FROM python:3.12-slim
WORKDIR /app
COPY engine-server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY Store_Scheduler_Backend/src/main/resources/python/ ./
COPY engine-server/app.py .
EXPOSE 8000
CMD ["sh", "-c", "exec uvicorn app:app --host 0.0.0.0 --port ${PORT:-8000}"]
