// Package nodeagent keeps a node's WireGuard peers in sync with the control plane.
// It talks to WireGuard only through the `wg` command, so it needs no cgo or extra modules.
package nodeagent

import (
	"bufio"
	"fmt"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"time"
)

type Peer struct {
	PublicKey  string
	AllowedIPs []string
	// LatestHandshake is zero if the peer has never completed a handshake.
	LatestHandshake time.Time
}

// ParseDump reads `wg show <iface> dump`. The first line describes the interface; each
// following line is: pubkey psk endpoint allowed-ips latest-handshake rx tx keepalive.
// Endpoints (client IPs) are read but deliberately discarded.
func ParseDump(out string) (map[string]Peer, error) {
	peers := map[string]Peer{}
	sc := bufio.NewScanner(strings.NewReader(out))
	first := true
	for sc.Scan() {
		line := sc.Text()
		if first {
			first = false
			continue
		}
		f := strings.Split(line, "\t")
		if len(f) < 8 {
			return nil, fmt.Errorf("unexpected wg dump line with %d fields", len(f))
		}
		var ips []string
		if f[3] != "(none)" && f[3] != "" {
			ips = strings.Split(f[3], ",")
			sort.Strings(ips)
		}
		var hs time.Time
		if sec, err := strconv.ParseInt(f[4], 10, 64); err == nil && sec > 0 {
			hs = time.Unix(sec, 0)
		}
		peers[f[0]] = Peer{PublicKey: f[0], AllowedIPs: ips, LatestHandshake: hs}
	}
	return peers, sc.Err()
}

type Change struct {
	Remove     bool
	PublicKey  string
	AllowedIPs []string
}

// Diff returns the `wg set` changes that turn [current] into [desired].
func Diff(current map[string]Peer, desired map[string][]string) []Change {
	var out []Change
	for key, ips := range desired {
		sorted := append([]string(nil), ips...)
		sort.Strings(sorted)
		cur, ok := current[key]
		if !ok || strings.Join(cur.AllowedIPs, ",") != strings.Join(sorted, ",") {
			out = append(out, Change{PublicKey: key, AllowedIPs: sorted})
		}
	}
	for key := range current {
		if _, ok := desired[key]; !ok {
			out = append(out, Change{Remove: true, PublicKey: key})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].PublicKey < out[j].PublicKey })
	return out
}

// ActiveCount: peers with a handshake within [window] (WireGuard re-keys every 2 min).
func ActiveCount(peers map[string]Peer, now time.Time, window time.Duration) int {
	n := 0
	for _, p := range peers {
		if !p.LatestHandshake.IsZero() && now.Sub(p.LatestHandshake) <= window {
			n++
		}
	}
	return n
}

// LoadPercent caps at 100.
func LoadPercent(active, capacity int) int {
	if capacity <= 0 {
		return 100
	}
	return min(100, active*100/capacity)
}

type WG struct{ Iface string }

func (w WG) Dump() (map[string]Peer, error) {
	out, err := exec.Command("wg", "show", w.Iface, "dump").Output()
	if err != nil {
		return nil, fmt.Errorf("wg show: %w", err)
	}
	return ParseDump(string(out))
}

func (w WG) Apply(c Change) error {
	args := []string{"set", w.Iface, "peer", c.PublicKey}
	if c.Remove {
		args = append(args, "remove")
	} else {
		args = append(args, "allowed-ips", strings.Join(c.AllowedIPs, ","))
	}
	if out, err := exec.Command("wg", args...).CombinedOutput(); err != nil {
		return fmt.Errorf("wg set: %v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}
