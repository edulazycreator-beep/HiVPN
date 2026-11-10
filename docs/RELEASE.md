# Release

## Before the first Play release

- [ ] Final `hivpn.applicationId` chosen (it cannot change afterwards)
- [ ] Upload key created; Play App Signing enabled; secrets in GitHub (BUILD_GUIDE.md)
- [ ] Repository variables set: `HIVPN_API_BASE_URL`, `HIVPN_DIRECTORY_PUBLIC_KEY`, `HIVPN_API_PINS`
- [ ] Play Console: VpnService declaration (VPN is the core function; traffic is encrypted to the operator's servers; no user data sold or used for ads)
- [ ] Data safety form matches PRIVACY.md
- [ ] Privacy policy URL published, with the same content as PRIVACY.md
- [ ] Legal review for each country you list the app in

## Each release

1. Run the automated suites and the manual plan in TESTING.md on the device matrix.
2. Bump `hivpn.versionName` and `hivpn.versionCode` in `gradle.properties`.
3. Update the changelog in the GitHub release notes.
4. Tag and push: `git tag v1.0.0 && git push --tags`.
5. CI builds the signed APK and AAB (Android workflow), plus the API image and node agent binaries (Backend workflow).
6. Upload the AAB to the Play Console internal track, then promote after testing.
7. Backend: `docker compose pull && docker compose up -d && docker compose exec api hivpn-api migrate`. Update node agents one location at a time.

## Version 0.9.0 status

Feature-complete for WireGuard. **Not yet verified on devices**, so do not publish until the TESTING.md manual plan passes. Version 1.0.0 is reserved for the first device-verified build.
