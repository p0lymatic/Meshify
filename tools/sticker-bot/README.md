# Sticker bot

Deploy with `docker build -t meshify-sticker-bot .` and run it with a persistent `/data` volume.
Set `TELEGRAM_BOT_TOKEN`; production publishing additionally requires a GitHub App installation ID, app ID and private key with repository-content write permission scoped to this repository. The bot retains rate-limit state in SQLite on the mounted volume. CI remains the final validator before a pack is published.
