package probe

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// Candidate represents a target endpoint to probe.
type Candidate struct {
	Address string // host:port
	Tag     string
}

// Result represents the probe outcome.
type Result struct {
	Candidate Candidate
	LatencyMs int64
	Err       error
}

// Prober provides fast, bounded connectivity probes.
type Prober struct {
	client *http.Client
}

// NewProber creates a Prober with sane timeouts.
func NewProber(proxyURL string) *Prober {
	transport := &http.Transport{
		DisableKeepAlives: true,
	}

	if proxyURL != "" {
		if parsed, err := url.Parse(proxyURL); err == nil {
			transport.Proxy = http.ProxyURL(parsed)
		}
	}

	return &Prober{
		client: &http.Client{
			Transport: transport,
			Timeout:   5 * time.Second,
		},
	}
}

// MeasureHTTPDelay sends a GET request to testUrl and returns the latency in milliseconds.
// Returns -1 on error.
func (p *Prober) MeasureHTTPDelay(ctx context.Context, testURL string) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, testURL, nil)
	if err != nil {
		return -1, err
	}

	start := time.Now()
	resp, err := p.client.Do(req)
	if err != nil {
		return -1, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 400 {
		return -1, fmt.Errorf("unexpected status code: %d", resp.StatusCode)
	}

	return time.Since(start).Milliseconds(), nil
}

// FastTCPProbe measures TCP handshake latency in milliseconds.
// Returns -1 if unreachable within timeout.
func FastTCPProbe(ctx context.Context, address string, timeout time.Duration) (int64, error) {
	var d net.Dialer
	d.Timeout = timeout

	start := time.Now()
	conn, err := d.DialContext(ctx, "tcp", address)
	if err != nil {
		return -1, err
	}
	_ = conn.Close()

	return time.Since(start).Milliseconds(), nil
}

// RaceParallel probes multiple candidates simultaneously and returns the first healthy one,
// or sorted results if multiple succeed.
func RaceParallel(ctx context.Context, candidates []Candidate, timeout time.Duration) []Result {
	var wg sync.WaitGroup
	results := make([]Result, len(candidates))

	for i, c := range candidates {
		wg.Add(1)
		go func(idx int, cand Candidate) {
			defer wg.Done()
			probeCtx, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()

			ms, err := FastTCPProbe(probeCtx, cand.Address, timeout)
			results[idx] = Result{
				Candidate: cand,
				LatencyMs: ms,
				Err:       err,
			}
		}(i, c)
	}

	wg.Wait()
	return results
}

// ChunkedResult represents the outcome of a ranged chunk throughput probe.
type ChunkedResult struct {
	TotalBytes      int64
	DurationSeconds float64
	ThroughputKBps  float64
	LatencyMs       int64
	Verdict         string // "ok" or "fail"
	Err             error
}

// FragmentConfig defines TLS fragmentation parameters.
type FragmentConfig struct {
	Packets  string `json:"packets"`
	Length   string `json:"length"`
	Interval string `json:"interval"`
}

// MatrixCandidate defines one candidate in the exploratory grid search.
type MatrixCandidate struct {
	Engine    string         `json:"engine"`
	Transport string         `json:"transport"`
	Port      int            `json:"port"`
	PortRange string         `json:"port_range,omitempty"`
	MTU       int            `json:"mtu"`
	Fragment  FragmentConfig `json:"fragment,omitempty"`
	CleanIP   string         `json:"clean_ip,omitempty"`
}

// DefaultMatrixCandidates generates the standard grid search matrix as specified in ADR-0007 / design 0001.
func DefaultMatrixCandidates() []MatrixCandidate {
	fragments := []FragmentConfig{
		{Packets: "1-3", Length: "10-50", Interval: "5-10"},
		{Packets: "1-3", Length: "100-200", Interval: "10-20"},
		{Packets: "1-3", Length: "300-600", Interval: "1-5"},
	}
	ports := []int{443, 8443, 2053, 25000}
	mtus := []int{1280, 1350, 1420}

	var candidates []MatrixCandidate
	// Reality fragment variations
	for _, f := range fragments {
		candidates = append(candidates, MatrixCandidate{
			Engine:    "xray",
			Transport: "reality",
			Port:      443,
			MTU:       1280,
			Fragment:  f,
		})
	}
	// Hysteria UDP port variations
	for _, p := range ports {
		candidates = append(candidates, MatrixCandidate{
			Engine:    "hysteria",
			Transport: "hy2",
			Port:      p,
			PortRange: "20000-50000",
			MTU:       1280,
		})
	}
	// MTU variations
	for _, m := range mtus {
		candidates = append(candidates, MatrixCandidate{
			Engine:    "xray",
			Transport: "reality",
			Port:      443,
			MTU:       m,
			Fragment:  fragments[1],
		})
	}
	return candidates
}

// ScoreCandidate calculates the throughput-centric score:
// score = throughputKBps * (1 - deathRate). Tie-break: lower latencyMs.
func ScoreCandidate(throughputKBps float64, latencyMs int64, deathRate float64) float64 {
	if throughputKBps <= 0 {
		return 0
	}
	dr := deathRate
	if dr < 0 {
		dr = 0
	} else if dr > 1 {
		dr = 1
	}
	score := throughputKBps * (1.0 - dr)
	if latencyMs > 0 && latencyMs < 5000 {
		score += (5000.0 - float64(latencyMs)) / 50000.0
	}
	return score
}

// MeasureChunkedThroughput downloads data in ranged chunks (default 512KB) from testURL
// and computes the throughput in KB/s.
func (p *Prober) MeasureChunkedThroughput(ctx context.Context, testURL string, chunkSize int64, numChunks int) ChunkedResult {
	if chunkSize <= 0 {
		chunkSize = 512 * 1024
	}
	if numChunks <= 0 {
		numChunks = 4 // 4 * 512KB = 2MB total for scan
	}

	start := time.Now()
	var totalBytes int64
	var initialLatency int64 = -1

	buffer := make([]byte, 32*1024)

	for i := 0; i < numChunks; i++ {
		rangeStart := int64(i) * chunkSize
		rangeEnd := rangeStart + chunkSize - 1

		req, err := http.NewRequestWithContext(ctx, http.MethodGet, testURL, nil)
		if err != nil {
			return ChunkedResult{Err: err, Verdict: "fail"}
		}
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", rangeStart, rangeEnd))

		chunkStart := time.Now()
		resp, err := p.client.Do(req)
		if err != nil {
			return ChunkedResult{
				TotalBytes: totalBytes,
				Err:        err,
				Verdict:    "fail",
			}
		}

		if i == 0 {
			initialLatency = time.Since(chunkStart).Milliseconds()
		}

		if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
			resp.Body.Close()
			return ChunkedResult{
				TotalBytes: totalBytes,
				Err:        fmt.Errorf("unexpected status %d", resp.StatusCode),
				Verdict:    "fail",
			}
		}

		var chunkRead int64
		for {
			n, rErr := resp.Body.Read(buffer)
			if n > 0 {
				chunkRead += int64(n)
				totalBytes += int64(n)
			}
			if rErr != nil {
				break
			}
		}
		resp.Body.Close()
	}

	duration := time.Since(start).Seconds()
	if duration <= 0 {
		duration = 0.001
	}

	throughputKBps := (float64(totalBytes) / 1024.0) / duration
	verdict := "ok"
	if throughputKBps < 500.0 { // T=500 KB/s threshold
		verdict = "fail"
	}

	return ChunkedResult{
		TotalBytes:      totalBytes,
		DurationSeconds: duration,
		ThroughputKBps:  throughputKBps,
		LatencyMs:       initialLatency,
		Verdict:         verdict,
	}
}
