package probe

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestScoreCandidate(t *testing.T) {
	// Zero or negative throughput -> score 0
	if s := ScoreCandidate(0, 100, 0); s != 0 {
		t.Errorf("expected 0 score for 0 throughput, got %f", s)
	}

	// 1000 KB/s with 0 death rate
	s1 := ScoreCandidate(1000, 100, 0)
	if s1 < 1000 {
		t.Errorf("expected score >= 1000, got %f", s1)
	}

	// 1000 KB/s with 0.1 death rate -> should be around 900
	s2 := ScoreCandidate(1000, 100, 0.1)
	if s2 >= s1 {
		t.Errorf("expected s2 < s1 due to death rate, got s2=%f, s1=%f", s2, s1)
	}

	// Lower latency gives slight tie-break advantage
	sFast := ScoreCandidate(1000, 50, 0)
	sSlow := ScoreCandidate(1000, 300, 0)
	if sFast <= sSlow {
		t.Errorf("expected lower latency to have higher score, got sFast=%f, sSlow=%f", sFast, sSlow)
	}
}

func TestDefaultMatrixCandidates(t *testing.T) {
	candidates := DefaultMatrixCandidates()
	if len(candidates) < 8 {
		t.Fatalf("expected at least 8 matrix candidates, got %d", len(candidates))
	}

	hasRealityFragment := false
	hasHysteriaPort := false
	for _, c := range candidates {
		if c.Engine == "xray" && c.Transport == "reality" && c.Fragment.Length != "" {
			hasRealityFragment = true
		}
		if c.Engine == "hysteria" && c.PortRange != "" {
			hasHysteriaPort = true
		}
	}

	if !hasRealityFragment {
		t.Error("expected reality fragment candidates in default matrix")
	}
	if !hasHysteriaPort {
		t.Error("expected hysteria port candidates in default matrix")
	}
}

func TestMeasureChunkedThroughput(t *testing.T) {
	// Dummy server supporting HTTP 206 Partial Content
	data := make([]byte, 2*1024*1024) // 2MB
	for i := range data {
		data[i] = byte(i % 256)
	}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rangeHdr := r.Header.Get("Range")
		if rangeHdr == "" {
			w.Header().Set("Content-Length", strconv.Itoa(len(data)))
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(data)
			return
		}

		// Parse bytes=start-end
		parts := strings.TrimPrefix(rangeHdr, "bytes=")
		bounds := strings.Split(parts, "-")
		start, _ := strconv.ParseInt(bounds[0], 10, 64)
		end := int64(len(data) - 1)
		if len(bounds) > 1 && bounds[1] != "" {
			end, _ = strconv.ParseInt(bounds[1], 10, 64)
		}

		if start > int64(len(data)) {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		if end >= int64(len(data)) {
			end = int64(len(data) - 1)
		}

		chunk := data[start : end+1]
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
		w.Header().Set("Content-Length", strconv.Itoa(len(chunk)))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(chunk)
	}))
	defer ts.Close()

	prober := NewProber("")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	chunkSize := int64(256 * 1024)
	numChunks := 2
	result := prober.MeasureChunkedThroughput(ctx, ts.URL, chunkSize, numChunks)

	if result.Err != nil {
		t.Fatalf("expected no error, got %v", result.Err)
	}
	if result.TotalBytes != chunkSize*int64(numChunks) {
		t.Fatalf("expected %d bytes, got %d", chunkSize*int64(numChunks), result.TotalBytes)
	}
	if result.ThroughputKBps <= 0 {
		t.Fatalf("expected positive throughput, got %f", result.ThroughputKBps)
	}
	if result.Verdict != "ok" {
		t.Fatalf("expected verdict 'ok' for local test, got %s", result.Verdict)
	}
}
