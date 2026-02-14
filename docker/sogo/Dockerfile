FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

# Install prerequisites
RUN apt-get update && apt-get install -y \
    gnupg2 curl apt-transport-https ca-certificates \
    apache2 memcached postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Add SOGo repository (Alinto nightly builds for Debian bookworm)
RUN curl -fsSL https://keys.openpgp.org/vks/v1/by-fingerprint/74FFC6D72B925A34B5D356BDF8A27B36A6E2EAE9 \
    | gpg --dearmor -o /usr/share/keyrings/sogo-nightly.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/sogo-nightly.gpg] https://packages.sogo.nu/nightly/5/debian bookworm bookworm" \
    > /etc/apt/sources.list.d/sogo.list

# Install SOGo
RUN apt-get update && apt-get install -y \
    sogo sope4.9-gdl1-postgresql \
    && rm -rf /var/lib/apt/lists/*

# Configure Apache for SOGo
RUN a2enmod proxy proxy_http headers rewrite

COPY sogo.conf /etc/sogo/sogo.conf
COPY apache-SOGo.conf /etc/apache2/conf-available/SOGo.conf
RUN a2enconf SOGo

COPY init-users.sql /docker-entrypoint-initdb.d/
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80

ENTRYPOINT ["/entrypoint.sh"]