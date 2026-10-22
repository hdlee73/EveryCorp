

def test_periodic_title_filter():
    from app.services.disclosures_service import is_periodic_title
    assert is_periodic_title("사업보고서 (2025.12)")
    assert is_periodic_title("[기재정정]반기보고서 (2026.06)")
    assert is_periodic_title("분기보고서 (2026.03)")
    assert not is_periodic_title("주요사항보고서(유상증자결정)")
    assert not is_periodic_title("감사보고서 (2025.12)")
