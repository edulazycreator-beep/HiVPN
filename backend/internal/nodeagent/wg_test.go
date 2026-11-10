package nodeagent

import (
	"testing"
	"time"
)

const dump = "privkey\tpubkey\t51820\toff\n" +
	"AAA=\t(none)\t203.0.113.5:4444\t10.64.1.1/32,fd64::101/128\t1700000000\t100\t200\t0\n" +
	"BBB=\t(none)\t(none)\t10.64.1.2/32\t0\t0\t0\t0\n"

func TestParseDump(t *testing.T) {
	peers, err := ParseDump(dump)
	if err != nil {
		t.Fatal(err)
	}
	if len(peers) != 2 {
		t.Fatalf("got %d peers", len(peers))
	}
	a := peers["AAA="]
	if a.LatestHandshake.Unix() != 1700000000 || len(a.AllowedIPs) != 2 {
		t.Fatalf("bad peer: %+v", a)
	}
	if !peers["BBB="].LatestHandshake.IsZero() {
		t.Fatal("never-handshaked peer must have zero time")
	}
}

func TestDiff(t *testing.T) {
	current, _ := ParseDump(dump)
	desired := map[string][]string{
		"AAA=": {"fd64::101/128", "10.64.1.1/32"}, // same set, different order → no change
		"BBB=": {"10.64.1.2/32", "fd64::102/128"}, // gains IPv6 → update
		"CCC=": {"10.64.1.3/32", "fd64::103/128"}, // new → add
	}
	current["DDD="] = Peer{PublicKey: "DDD="} // revoked → remove

	changes := Diff(current, desired)
	if len(changes) != 3 {
		t.Fatalf("got %d changes: %+v", len(changes), changes)
	}
	if changes[0].PublicKey != "BBB=" || changes[1].PublicKey != "CCC=" || !changes[2].Remove || changes[2].PublicKey != "DDD=" {
		t.Fatalf("unexpected changes: %+v", changes)
	}
}

func TestLoad(t *testing.T) {
	peers, _ := ParseDump(dump)
	now := time.Unix(1700000060, 0)
	if n := ActiveCount(peers, now, 3*time.Minute); n != 1 {
		t.Fatalf("active = %d, want 1", n)
	}
	if LoadPercent(250, 500) != 50 || LoadPercent(900, 500) != 100 || LoadPercent(1, 0) != 100 {
		t.Fatal("load percent wrong")
	}
}
