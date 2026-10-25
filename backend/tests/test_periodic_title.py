

def test_periodic_title_filter():
    from app.services.disclosures_service import is_periodic_title
    assert is_periodic_title("사업보고서 (2025.12)")
    assert is_periodic_title("[기재정정]반기보고서 (2026.06)")
    assert is_periodic_title("분기보고서 (2026.03)")
    assert not is_periodic_title("주요사항보고서(유상증자결정)")
    assert not is_periodic_title("감사보고서 (2025.12)")


def test_is_major_title():
    from app.services.disclosures_service import is_major_title
    assert is_major_title("주요사항보고서(유상증자결정)")
    assert is_major_title("[기재정정]주요사항보고서(전환사채권발행결정)")
    assert not is_major_title("사업보고서 (2025.12)")
    assert not is_major_title("임원ㆍ주요주주특정증권등소유상황보고서")
