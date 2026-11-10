// hivpn-api: the Hi VPN control-plane API and its admin commands.
//
//	hivpn-api serve                      run the public API and the node (mTLS) API
//	hivpn-api migrate                    apply database migrations
//	hivpn-api keygen <out.pem>           create the directory signing key; prints the app's public key
//	hivpn-api server-add <flags>         add or update a VPN node in the directory
//	hivpn-api grant <number> <plan> <days>  set an account's plan (until billing exists)
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"database/sql"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"

	"hivpn/backend/internal/auth"
	"hivpn/backend/internal/config"
	"hivpn/backend/internal/directory"
	"hivpn/backend/internal/httpapi"
	"hivpn/backend/internal/store"
	"hivpn/backend/migrations"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	var err error
	switch cmd {
	case "keygen":
		err = keygen(os.Args[2:])
	case "serve", "migrate", "server-add", "grant":
		err = withDB(cmd, os.Args[2:], log)
	default:
		err = fmt.Errorf("unknown command %q", cmd)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func keygen(args []string) error {
	if len(args) != 1 {
		return errors.New("usage: hivpn-api keygen <out.pem>")
	}
	pemBytes, signer, err := directory.GenerateKeyPEM()
	if err != nil {
		return err
	}
	if err := os.WriteFile(args[0], pemBytes, 0o600); err != nil {
		return err
	}
	fmt.Println("Directory signing key written to", args[0])
	fmt.Println("Put this in the app's gradle.properties (or CI -P flag):")
	fmt.Println("hivpn.directoryPublicKey=" + signer.PublicKeyBase64())
	return nil
}

func withDB(cmd string, args []string, log *slog.Logger) error {
	cfg, err := config.FromEnv()
	if err != nil {
		return err
	}
	pg, err := store.OpenPostgres(cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("database: %w", err)
	}
	ctx := context.Background()
	switch cmd {
	case "migrate":
		return migrate(ctx, pg.DB(), log)
	case "server-add":
		return serverAdd(ctx, pg, args)
	case "grant":
		return grant(ctx, pg, cfg, args)
	}
	return serve(cfg, pg, log)
}

func migrate(ctx context.Context, db *sql.DB, log *slog.Logger) error {
	if _, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	files, err := fs.Glob(migrations.FS, "*.sql")
	if err != nil {
		return err
	}
	sort.Strings(files)
	for _, f := range files {
		v, err := strconv.Atoi(strings.SplitN(f, "_", 2)[0])
		if err != nil {
			return fmt.Errorf("bad migration name %s", f)
		}
		var exists bool
		if err := db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version=$1)`, v).Scan(&exists); err != nil {
			return err
		}
		if exists {
			continue
		}
		body, _ := migrations.FS.ReadFile(f)
		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, string(body)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("%s: %w", f, err)
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations(version) VALUES ($1)`, v); err != nil {
			_ = tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
		log.Info("migration applied", "file", f)
	}
	return nil
}

func serverAdd(ctx context.Context, st store.Store, args []string) error {
	fsFlags := flag.NewFlagSet("server-add", flag.ContinueOnError)
	s := store.Server{}
	tier := fsFlags.String("tier", "FREE", "FREE, PREMIUM or PRO")
	cats := fsFlags.String("categories", "STANDARD", "comma-separated: STANDARD,HIGH_SPEED,LOW_LATENCY,STREAMING,P2P")
	fsFlags.StringVar(&s.ID, "id", "", "node id; must equal the node certificate CN (e.g. sg-sin-01)")
	fsFlags.StringVar(&s.Name, "name", "", "display name")
	fsFlags.StringVar(&s.CountryCode, "country-code", "", "ISO code, e.g. SG")
	fsFlags.StringVar(&s.CountryName, "country", "", "country name")
	fsFlags.StringVar(&s.City, "city", "", "city")
	fsFlags.StringVar(&s.Hostname, "host", "", "public hostname or IP")
	fsFlags.IntVar(&s.Port, "port", 51820, "WireGuard UDP port")
	fsFlags.StringVar(&s.PublicKey, "pubkey", "", "node WireGuard public key")
	fsFlags.IntVar(&s.Capacity, "capacity", 500, "peers that count as 100% load")
	if err := fsFlags.Parse(args); err != nil {
		return err
	}
	if s.ID == "" || s.Hostname == "" || !httpapi.ValidWireGuardKey(s.PublicKey) || s.CountryCode == "" {
		return errors.New("id, host, pubkey (valid WireGuard key) and country-code are required")
	}
	s.Tier = store.Tier(strings.ToUpper(*tier))
	s.Categories = strings.Split(strings.ToUpper(*cats), ",")
	s.Status = "OFFLINE" // becomes ONLINE when the node agent reports health
	if s.Name == "" {
		s.Name = s.ID
	}
	if err := st.UpsertServer(ctx, s); err != nil {
		return err
	}
	return st.Audit(ctx, adminActor(), "server-add", s.ID)
}

func grant(ctx context.Context, st store.Store, cfg *config.Config, args []string) error {
	if len(args) != 3 {
		return errors.New("usage: hivpn-api grant <account-number> <free|premium|pro> <days>")
	}
	number := auth.NormalizeNumber(args[0])
	plan := store.Plan(args[1])
	days, err := strconv.Atoi(args[2])
	if number == "" || err != nil || (plan != store.PlanFree && plan != store.PlanPremium && plan != store.PlanPro) {
		return errors.New("invalid arguments")
	}
	acct, err := st.AccountByNumberHash(ctx, auth.HashNumber(cfg.AccountPepper, number))
	if err != nil {
		return err
	}
	var exp *time.Time
	if plan != store.PlanFree {
		t := time.Now().Add(time.Duration(days) * 24 * time.Hour)
		exp = &t
	}
	if err := st.SetPlan(ctx, acct.ID, plan, exp); err != nil {
		return err
	}
	return st.Audit(ctx, adminActor(), "grant "+string(plan)+" "+args[2]+"d", strconv.FormatInt(acct.ID, 10))
}

func adminActor() string {
	if u := os.Getenv("SUDO_USER"); u != "" {
		return u
	}
	if u := os.Getenv("USER"); u != "" {
		return u
	}
	return "admin"
}

func serve(cfg *config.Config, st store.Store, log *slog.Logger) error {
	dirSigner, err := directory.LoadSigner(cfg.DirectoryKeyFile)
	if err != nil {
		return fmt.Errorf("directory key: %w", err)
	}
	api := httpapi.New(st, auth.NewSigner(cfg.TokenSecret), dirSigner, httpapi.Options{
		AccessTTL:     cfg.AccessTokenTTL,
		RefreshTTL:    cfg.RefreshTokenTTL,
		DirectoryTTL:  cfg.DirectoryTTL,
		AccountPepper: cfg.AccountPepper,
		TrustedProxy:  cfg.TrustedProxy,
		BillingReady:  cfg.GooglePlayPackage != "",
	}, log)

	public := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           api.PublicHandler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	servers := []*http.Server{public}

	if cfg.NodeCAFile != "" {
		caPEM, err := os.ReadFile(cfg.NodeCAFile)
		if err != nil {
			return err
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(caPEM) {
			return errors.New("node CA: no certificates")
		}
		node := &http.Server{
			Addr:              cfg.NodeListenAddr,
			Handler:           api.NodeHandler(),
			ReadHeaderTimeout: 10 * time.Second,
			TLSConfig: &tls.Config{
				MinVersion: tls.VersionTLS13,
				ClientAuth: tls.RequireAndVerifyClientCert,
				ClientCAs:  pool,
			},
		}
		servers = append(servers, node)
		go func() {
			log.Info("node API listening", "addr", cfg.NodeListenAddr)
			if err := node.ListenAndServeTLS(cfg.NodeCertFile, cfg.NodeKeyFile); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Error("node API", "err", err)
				os.Exit(1)
			}
		}()
	} else {
		log.Warn("HIVPN_NODE_CA not set: node API disabled")
	}

	go func() {
		log.Info("public API listening", "addr", cfg.ListenAddr)
		if err := public.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("public API", "err", err)
			os.Exit(1)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	for _, s := range servers {
		_ = s.Shutdown(ctx)
	}
	return nil
}
