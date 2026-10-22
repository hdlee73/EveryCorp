"""회사명 정규화·초성 검색 유틸."""
import re

_CHOSUNG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
_LEGAL_FORMS = re.compile(r"\(주\)|㈜|\(유\)|\(재\)|\(사\)|주식회사|유한회사|유한책임회사|합자회사|합명회사")
_NON_WORD = re.compile(r"[\s\.\,\-\_\(\)\[\]&·]+")


def normalize_name(name: str) -> str:
    """'(주)삼성 전자' → '삼성전자'. 대소문자 무시."""
    n = _LEGAL_FORMS.sub("", name or "")
    n = _NON_WORD.sub("", n)
    return n.lower()


def to_chosung(text: str) -> str:
    out = []
    for ch in text:
        code = ord(ch) - 0xAC00
        if 0 <= code < 11172:
            out.append(_CHOSUNG[code // 588])
        else:
            out.append(ch)
    return "".join(out)


def is_chosung_query(q: str) -> bool:
    return bool(q) and all(c in _CHOSUNG for c in q)
