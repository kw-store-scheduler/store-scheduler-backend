"""
최적화 엔진 HTTP 서버 (FastAPI)

백엔드가 ENGINE_URL을 설정하면 서브프로세스 대신 이 서버의 POST /solve로 엔진을 호출한다.
입력·출력 JSON은 run_engine.py(stdin/stdout)와 같고, 실제 풀이도 run_engine.solve()를 그대로 쓴다.
ENGINE_TOKEN이 설정되어 있으면 X-Engine-Token 헤더가 같은 요청만 받는다.
"""

import os

from fastapi import Body, FastAPI, Header, HTTPException

from run_engine import solve

app = FastAPI(title="Store Scheduler Engine")
ENGINE_TOKEN = os.environ.get("ENGINE_TOKEN", "")


@app.get("/health")
def health():
    return {"status": "ok"}


# 솔버가 CPU를 오래 쓰므로 async가 아닌 일반 함수로 둬서 스레드 풀에서 실행되게 한다
@app.post("/solve")
def solve_endpoint(config: dict = Body(...), x_engine_token: str = Header(default="")):
    if ENGINE_TOKEN and x_engine_token != ENGINE_TOKEN:
        raise HTTPException(status_code=401, detail="invalid engine token")
    return solve(config)
