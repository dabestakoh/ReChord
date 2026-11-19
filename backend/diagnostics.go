package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"
)

const diagnosticGitHubEndpoint = "https://api.github.com/repos/dabestakoh/ReChord/issues"

func newDiagnosticHandler() http.HandlerFunc {
	token := strings.TrimSpace(os.Getenv("RECHORD_DIAGNOSTICS_GITHUB_TOKEN"))
	client := &http.Client{
		Timeout: 10 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return diagnosticHandler(token, client, diagnosticGitHubEndpoint)
}

func diagnosticHandler(token string, client *http.Client, endpoint string) http.HandlerFunc {
	perIP := newIPRateLimiter(time.Hour, 3, 4096)
	total := newIPRateLimiter(time.Hour, 20, 1)

	return func(w http.ResponseWriter, r *http.Request) {
		if token == "" {
			jsonError(w, http.StatusServiceUnavailable, "diagnostics_unavailable", "Diagnostic submissions are unavailable.")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 16*1024)
		var input struct {
			Report string `json:"report"`
		}
		if !decodeJSONBody(w, r, &input) {
			return
		}
		report := sanitizedDiagnostics(input.Report)
		if report == "" {
			jsonError(w, http.StatusUnprocessableEntity, "invalid_report", "Invalid diagnostic report.")
			return
		}
		if !perIP.Allow(clientIP(r)) || !total.Allow("all") {
			jsonError(w, http.StatusTooManyRequests, "rate_limited", "Try again later.")
			return
		}

		body, _ := json.Marshal(map[string]string{
			"title": "ReChord diagnostic report",
			"body":  "Submitted from ReChord Settings.\n\n```text\n" + report + "\n```",
		})
		req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, endpoint, bytes.NewReader(body))
		if err != nil {
			jsonError(w, http.StatusBadGateway, "issue_failed", "Could not submit the report.")
			return
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Accept", "application/vnd.github+json")
		req.Header.Set("Content-Type", "application/json")
		response, err := client.Do(req)
		if err != nil {
			jsonError(w, http.StatusBadGateway, "issue_failed", "Could not submit the report.")
			return
		}
		defer response.Body.Close()
		var issue struct {
			Number int `json:"number"`
		}
		if response.StatusCode != http.StatusCreated ||
			json.NewDecoder(io.LimitReader(response.Body, 16*1024)).Decode(&issue) != nil ||
			issue.Number <= 0 {
			jsonError(w, http.StatusBadGateway, "issue_failed", "Could not submit the report.")
			return
		}
		jsonResponse(w, http.StatusCreated, issue)
	}
}

var diagnosticHeader = regexp.MustCompile(`^ReChord [0-9]+(\.[0-9]+){1,4}(-[A-Za-z0-9.-]{1,20})? \([0-9]{1,10}, (debug|release|benchmark)\)$`)
var diagnosticBreadcrumb = regexp.MustCompile(`^(Android SDK [0-9]{1,3}|Playback error -?[0-9]{1,8}|(App crash|Native crash|App not responding|Low memory kill) \(time: [0-9]{1,16}\)|Playback memory \((track|error|rebuild|quality|pressure)\): heap [0-9]{1,12}/[0-9]{1,12} B, active [0-9]{1,10} B, standby [0-9]{1,10} B, quality [0-9]{1,10} B, route [A-Z0-9_]{1,30}, transport [A-Z0-9_]{1,30}, mode [A-Z0-9_]{1,30}|USB open: [A-Z_]{1,30}, [0-9]{1,7} Hz → [0-9]{1,7} Hz, [0-9]{1,3} → [0-9]{1,3} valid bits)$`)
var diagnosticSymbol = regexp.MustCompile(`^(  )?([A-Za-z0-9_$<>-]+\.)+[A-Za-z0-9_$<>-]+(:-?[0-9]{1,8})?$`)

func sanitizedDiagnostics(report string) string {
	lines := strings.Split(strings.ReplaceAll(report, "\r\n", "\n"), "\n")
	if !diagnosticHeader.MatchString(lines[0]) {
		return ""
	}
	allowed := make([]string, 0, 120)
	for _, line := range lines {
		switch line {
		case "", "Last recorded crash", "Recent Android process exits", "Recent app events",
			"Uncaught exception", "Shared app exception", "Exclusive audio fallback", "USB volume handoff failed":
			allowed = append(allowed, line)
		default:
			if len(line) <= 256 && (diagnosticHeader.MatchString(line) ||
				diagnosticBreadcrumb.MatchString(line) || diagnosticSymbol.MatchString(line)) {
				allowed = append(allowed, line)
			}
		}
		if len(allowed) == 120 {
			break
		}
	}
	return strings.TrimSpace(strings.Join(allowed, "\n"))
}
