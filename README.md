# Regax Crypto Agent

A simple Java utility and web application for live chat, simulated crypto deals, and wallet management.

## Run

Compile the Java server with Java 11 or later:

```bash
javac CryptoWebApp.java
```

Start the application:

```bash
java CryptoWebApp
```

Open your browser to:

```bash
http://localhost:8080
```

## Features

- Live chat window with market messages
- Simulated buy / sell / hold crypto deals
- Wallet balance and holdings tracking
- Sign up / sign in with a local account
- Phantom wallet sign-in support for browser-based Solana wallets
- Real Binance API credential verification and balance retrieval
- Current prices for Solana, Bitcoin, Ethereum, and Dogecoin

## Notes

- This is a simulation example only, not a real trading system.
- Orders are executed against a mock wallet and mock market prices.
- Binance API credentials are verified and can be used to fetch real account balances.
- Credentials are stored only in memory in this demo; do not use this as a production wallet or key vault.
- Do not use for actual investment or financial decisions.

## Deploying to a free container host

This app can be deployed on free container-based hosts such as Railway or Fly.io.

### Using Railway

1. Create an account on https://railway.app
2. Create a new project and connect your GitHub repository.
3. Railway should detect the `Dockerfile` and build the container.
4. Set `PORT=8080` if Railway does not automatically detect it.
5. Deploy and open the generated URL.

### Using Fly.io

1. Install the Fly CLI: https://fly.io/docs/get-started/install/
2. Run `fly launch` in this repository and follow the prompts.
3. Choose `Dockerfile` when asked.
4. Run `fly deploy`.

### Local Docker build

```bash
docker build -t regax-crypto-webapp .
docker run -p 8080:8080 regax-crypto-webapp
```

Then open:

```bash
http://localhost:8080
```

### Google Cloud Run

A GitHub Actions workflow is configured at `.github/workflows/cloud-run-deploy.yml`.
On every push to `main`, the app can be built and deployed to Google Cloud Run.

To use this workflow, set these repository secrets in GitHub:

- `GCP_PROJECT_ID` — your Google Cloud project ID
- `GCP_REGION` — the Cloud Run region, e.g. `us-central1`
- `GCP_SA_KEY` — JSON key for a service account with these roles:
  - `roles/run.admin`
  - `roles/storage.admin`
  - `roles/iam.serviceAccountUser`

Example service account setup using `gcloud` on your local machine:

```bash
PROJECT_ID=your-gcp-project-id
SA_NAME=github-actions-deployer

gcloud iam service-accounts create $SA_NAME \
  --project "$PROJECT_ID" \
  --display-name "GitHub Actions Cloud Run deployer"

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member "serviceAccount:$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com" \
  --role roles/run.admin

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member "serviceAccount:$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com" \
  --role roles/storage.admin

gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member "serviceAccount:$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com" \
  --role roles/iam.serviceAccountUser

gcloud iam service-accounts keys create key.json \
  --iam-account "$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com" \
  --project "$PROJECT_ID"
```

Then copy the contents of `key.json` into the `GCP_SA_KEY` secret.

Push to `main` or trigger the workflow manually from GitHub.

### GitHub Container Registry

A GitHub Actions workflow is also configured at `.github/workflows/build-and-publish.yml`.
On every push to `main`, the app image is built and published to:

```text
ghcr.io/<your-github-username>/regax-crypto-webapp:latest
```

You can then deploy that container image to any free host that supports Docker.
