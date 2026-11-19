package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDiagnosticsCreatesIssueAndReturnsNumber(t *testing.T) {
	var posted map[string]string
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-token" || r.Method != http.MethodPost {
			t.Errorf("wrong request to GitHub: %s", r.Method)
		}
		_ = json.NewDecoder(r.Body).Decode(&posted)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"number":42}`))
	}))
	defer remote.Close()
	request := httptest.NewRequest(http.MethodPost, "/api/diagnostics", strings.NewReader(
		`{"report":"ReChord 1.9.2.8 (51, debug)\nAndroid SDK 37\nLast recorded crash\n  com.music.Player.run:42\nhttps://private.example/?token=secret\n@username"}`,
	))
	response := httptest.NewRecorder()
	diagnosticHandler("test-token", remote.Client(), remote.URL).ServeHTTP(response, request)
	if response.Code != http.StatusCreated || !strings.Contains(response.Body.String(), `"number":42`) {
		t.Fatalf("unexpected response: %d %s", response.Code, response.Body.String())
	}
	if got := posted["body"]; !strings.Contains(got, "com.music.Player.run:42") ||
		strings.Contains(got, "secret") || strings.Contains(got, "@username") {
		t.Fatalf("unexpected GitHub issue body: %q", got)
	}
}

func TestDiagnosticsIsUnavailableWithoutServerCredential(t *testing.T) {
	response := httptest.NewRecorder()
	diagnosticHandler("", nil, "").ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/api/diagnostics", nil))
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", response.Code)
	}
}
