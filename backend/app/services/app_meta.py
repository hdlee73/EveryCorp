"""앱 정보·업데이트 정보.

앱은 시작 시 /api/v1/app/meta 를 호출해 최신 버전·강제 업데이트 여부를 확인한다.
(서버가 내려가 있어도 업데이트가 막히지 않도록 앱은 GitHub Releases API를 직접 폴백 조회한다.)
릴리스는 이 레포 쓰기 권한자만 만들 수 있고, APK는 GitHub Secrets의 비공개 키로만 서명되므로
포크·제3자가 '업데이트'를 배포할 수 없다(서명이 다르면 안드로이드가 설치를 거부).
"""
from __future__ import annotations

from ..cache import cached
from ..config import APP_NAME, APP_NAME_KO, AUTHOR_EMAIL, AUTHOR_NAME, GITHUB_REPO, get_settings
from ..http import get_client


def _version_tuple(v: str) -> tuple[int, ...]:
    core = v.strip().lstrip("vV").split("-")[0]
    try:
        return tuple(int(x) for x in core.split("."))
    except ValueError:
        return (0,)


def is_newer(latest: str, current: str) -> bool:
    return _version_tuple(latest) > _version_tuple(current)


def extract_changes(body: str) -> str:
    """릴리스 본문에서 '## ... 변경 내용' 구역만(FSS Insights와 같은 규칙)."""
    lines = (body or "").splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith("#") and "변경 내용" in l), None)
    if start is None:
        return (body or "").strip()
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("#")), len(lines))
    return "\n".join(lines[start + 1:end]).strip()


@cached(ttl=30 * 60)
async def latest_release() -> dict | None:
    headers = {"Accept": "application/vnd.github+json"}
    if tok := get_settings().github_token:
        headers["Authorization"] = f"Bearer {tok}"
    r = await get_client().get(f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest", headers=headers)
    if r.status_code != 200:
        return None
    j = r.json()
    assets = j.get("assets") or []
    apk = next((a for a in assets if a.get("name", "").endswith(".apk")), None)
    sha = next((a for a in assets if a.get("name", "").endswith(".apk.sha256")), None)
    return {
        "version": (j.get("tag_name") or "").lstrip("v"),
        "published_at": j.get("published_at"),
        "notes": extract_changes(j.get("body") or ""),
        "release_url": j.get("html_url"),
        "apk_url": apk.get("browser_download_url") if apk else None,
        "apk_size": apk.get("size") if apk else None,
        "sha256_url": sha.get("browser_download_url") if sha else None,
    }


async def get_meta(current_version: str | None) -> dict:
    s = get_settings()
    latest = None
    try:
        latest = await latest_release()
    except Exception:  # noqa: BLE001
        latest = None
    update_available = bool(latest and current_version and is_newer(latest["version"], current_version))
    force = bool(current_version and is_newer(s.min_supported_version, current_version))
    return {
        "app_name": APP_NAME,
        "app_name_ko": APP_NAME_KO,
        "author": {"name": AUTHOR_NAME, "email": AUTHOR_EMAIL},
        "repository": f"https://github.com/{GITHUB_REPO}",
        "min_supported_version": s.min_supported_version,
        "current_version": current_version,
        "update_available": update_available,
        "force_update": force,
        "latest": latest,
    }
