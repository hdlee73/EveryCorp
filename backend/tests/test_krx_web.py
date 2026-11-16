import httpx

from app.errors import UpstreamError
from app.services import krx_web as kw


class S:
    krx_id = "id"
    krx_pw = "pw"
    http_timeout = 5.0
    user_agent = "test"


def _web(monkeypatch, handler, creds=True):
    s = S()
    if not creds:
        s.krx_id = s.krx_pw = ""
    monkeypatch.setattr(kw, "get_settings", lambda: s)
    w = kw.KrxWeb()
    w._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return w


async def test_logout_then_login_and_retry(monkeypatch):
    state = {"logged": False, "logins": 0}

    def handler(req: httpx.Request):
        if req.url.path.endswith("MDCCOMS001D1.cmd"):
            state["logins"] += 1
            body = req.content.decode()
            if "skipDup=Y" not in body:
                return httpx.Response(200, json={"_error_code": "CD011"})
            state["logged"] = True
            return httpx.Response(200, json={"_error_code": "CD001"})
        if req.url.path.endswith("getJsonData.cmd"):
            return httpx.Response(200, json={"output": [1]}) if state["logged"] else httpx.Response(400, text="LOGOUT")
        return httpx.Response(200, text="ok")

    w = _web(monkeypatch, handler)
    assert await w.post_json({"bld": "x"}) == {"output": [1]}
    assert state["logins"] == 2 and w.status()["logged_in"]


async def test_logout_without_credentials_gives_hint(monkeypatch):
    w = _web(monkeypatch, lambda req: httpx.Response(400, text="LOGOUT"), creds=False)
    try:
        await w.post_json({"bld": "x"})
        raise AssertionError
    except UpstreamError as e:
        assert "KRX_ID" in str(e)


async def test_bad_password_reported(monkeypatch):
    def handler(req):
        if req.url.path.endswith("MDCCOMS001D1.cmd"):
            return httpx.Response(200, json={"_error_code": "CD999", "_error_message": "비밀번호 오류"})
        return httpx.Response(400, text="LOGOUT") if req.url.path.endswith("getJsonData.cmd") else httpx.Response(200)
    w = _web(monkeypatch, handler)
    try:
        await w.post_json({"bld": "x"})
        raise AssertionError
    except UpstreamError as e:
        assert "로그인 실패" in str(e) and "pw" not in w.login_error
