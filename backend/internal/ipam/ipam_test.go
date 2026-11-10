package ipam

import "testing"

func TestAddresses(t *testing.T) {
	cases := []struct {
		i      int64
		v4, v6 string
	}{
		{1, "10.64.1.1/32", "fd64::101/128"},
		{255, "10.64.1.255/32", "fd64::1ff/128"},
		{256, "10.64.2.0/32", "fd64::200/128"},
		{MaxIndex, "10.127.255.255/32", "fd64::3f:ffff/128"},
	}
	for _, c := range cases {
		v4, v6, err := Addresses(c.i)
		if err != nil || v4 != c.v4 || v6 != c.v6 {
			t.Errorf("Addresses(%d) = %s %s %v, want %s %s", c.i, v4, v6, err, c.v4, c.v6)
		}
	}
	for _, bad := range []int64{0, -1, MaxIndex + 1} {
		if _, _, err := Addresses(bad); err == nil {
			t.Errorf("Addresses(%d) should fail", bad)
		}
	}
}

func TestNeverHandsOutGateway(t *testing.T) {
	// 10.64.0.0/24 is reserved for node gateways (10.64.0.1 = DNS).
	for i := int64(1); i <= 300; i++ {
		v4, _, _ := Addresses(i)
		if v4[:8] == "10.64.0." {
			t.Fatalf("index %d got reserved address %s", i, v4)
		}
	}
}
