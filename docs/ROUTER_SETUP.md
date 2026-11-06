# Connecting NetPilot to a MikroTik Router

NetPilot talks to the router through the **RouterOS REST API**. It needs **RouterOS 7.1 or newer**.
Check the version in WinBox under **System → Resources → Version**.

Run the commands below in **WinBox → New Terminal**. Replace the example values (`10.0.0.1`, `10.0.0.0/24`) with your own.

## 1. Create a dedicated user (recommended)

Don't connect NetPilot with the `admin` account. Create a group with only the rights NetPilot needs:

```text
/user group add name=netpilot policy=read,write,api,rest-api,!local,!telnet,!ssh,!ftp,!reboot,!policy,!test,!winbox,!password,!web,!sniff,!sensitive,!romon
/user add name=netpilot group=netpilot password="CHOOSE-A-LONG-PASSWORD"
```

- `read` and `rest-api` are enough to connect and load profiles and servers.
- `write` will be needed in a later version, to create hotspot users directly on the router.
- If NetPilot reports *"may not use the REST API"*, check the group's policies.

## 2. Enable HTTPS (recommended)

Over plain HTTP, the password travels unencrypted. To enable HTTPS, create a certificate and attach it to the `www-ssl` service:

```text
/certificate add name=netpilot-ca common-name=netpilot-ca key-usage=key-cert-sign,crl-sign days-valid=3650
/certificate sign netpilot-ca
/certificate add name=router-https common-name=10.0.0.1 subject-alt-name=IP:10.0.0.1 days-valid=3650
/certificate sign router-https ca=netpilot-ca
/ip service set www-ssl certificate=router-https disabled=no
```

Signing can take a few seconds on small routers.

### Limit who can reach the API

```text
/ip service set www-ssl address=10.0.0.0/24
```

If you only use HTTPS, you can turn plain HTTP off with `/ip service set www disabled=yes`. Note that WebFig over HTTP stops working too.

## 3. Connect from NetPilot

1. **Router → Connect…** (or click *Router: not connected* in the status bar).
2. Enter the address, keep **Use HTTPS** checked (port 443), and enter the user name and password.
3. Press **Test** to check, or **Connect**.

### First HTTPS connection: confirm the certificate

Router certificates are self-signed, so NetPilot asks you to confirm the certificate once and then remembers its **SHA-256 fingerprint**.

Before pressing **Trust**, compare the fingerprint NetPilot shows with the one on the router:

```text
/certificate print detail where name=router-https
```

Look at the `fingerprint=` value, or in WinBox open **System → Certificates → router-https → Fingerprint**.

If NetPilot later says **"Router certificate changed!"**, someone may be intercepting the connection. Only press *Trust new certificate* if you replaced the certificate yourself.

## Security notes

- **The password is never saved.** You type it each time NetPilot starts. It is not written to settings, logs or error messages.
- NetPilot saves only the address, port, user name and the trusted certificate fingerprints, in `~/.netpilot/settings.properties`.
- Use **Router → Forget** in the connection dialog if you replace the router's certificate.

## Troubleshooting

| Message | What to check |
|---|---|
| Wrong user name or password | User and password; the user must exist under **System → Users** |
| May not use the REST API | The user's group needs `read` and `rest-api` |
| Could not connect … service is enabled | **IP → Services**: `www-ssl` (HTTPS) or `www` (HTTP) enabled, correct port, and `address` allows your PC |
| The HTTPS connection failed | `www-ssl` has no certificate (see step 2), or you used the HTTP port with HTTPS |
| The router did not answer | Address, cable/Wi-Fi, and firewall rules on the router |
| The router does not offer … | RouterOS older than 7.1 |
