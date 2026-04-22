"""Tests for SCM exception classes."""

from ai_driven.scm.exceptions import (
    SCMAuthError,
    SCMError,
    SCMNotFoundError,
    SCMRateLimitError,
    SCMValidationError,
)


class TestSCMExceptions:
    def test_scm_error_is_base(self) -> None:
        assert issubclass(SCMNotFoundError, SCMError)
        assert issubclass(SCMRateLimitError, SCMError)
        assert issubclass(SCMValidationError, SCMError)
        assert issubclass(SCMAuthError, SCMError)

    def test_rate_limit_error_default_retry(self) -> None:
        err = SCMRateLimitError()
        assert err.retry_after == 60
        assert "60s" in str(err)

    def test_rate_limit_error_custom_retry(self) -> None:
        err = SCMRateLimitError(retry_after=120)
        assert err.retry_after == 120
        assert "120s" in str(err)

    def test_not_found_error_message(self) -> None:
        err = SCMNotFoundError("repo not found")
        assert str(err) == "repo not found"

    def test_auth_error_message(self) -> None:
        err = SCMAuthError("bad token")
        assert str(err) == "bad token"
