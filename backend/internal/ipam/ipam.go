// Package ipam maps a device's host index to its tunnel addresses.
//
// Every node uses the same tunnel subnets, so a device keeps one address on all nodes:
//
//	IPv4 10.64.0.0/10 — 10.64.0.0/24 is reserved for node gateways (10.64.0.1 = DNS)
//	IPv6 fd64::/64    — fd64::1 is the node gateway / DNS
package ipam

import (
	"fmt"
	"net/netip"
)

const (
	reserved = 256     // first /24 of the IPv4 range
	ipv4Size = 1 << 22 // /10
	MaxIndex = ipv4Size - reserved - 1
)

var (
	v4Base = netip.MustParseAddr("10.64.0.0").As4()
	v6Base = netip.MustParseAddr("fd64::").As16()
)

// Addresses returns the device's IPv4 /32 and IPv6 /128 for host index i (1-based).
func Addresses(i int64) (string, string, error) {
	if i < 1 || i > MaxIndex {
		return "", "", fmt.Errorf("host index %d out of range", i)
	}
	host := uint32(i) + reserved
	b := v4Base
	n := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
	n += host
	v4 := netip.AddrFrom4([4]byte{byte(n >> 24), byte(n >> 16), byte(n >> 8), byte(n)})

	v6b := v6Base
	v6b[12], v6b[13], v6b[14], v6b[15] = byte(host>>24), byte(host>>16), byte(host>>8), byte(host)
	v6 := netip.AddrFrom16(v6b)
	return v4.String() + "/32", v6.String() + "/128", nil
}
