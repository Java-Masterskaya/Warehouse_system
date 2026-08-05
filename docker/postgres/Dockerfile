FROM postgres:16

RUN apt-get update && \
    apt-get install -y git build-essential postgresql-server-dev-16 && \
    git clone https://github.com/pgpartman/pg_partman.git /tmp/pg_partman && \
    cd /tmp/pg_partman && \
    make && make install && \
    cd / && rm -rf /tmp/pg_partman && \
    apt-get remove -y git build-essential postgresql-server-dev-16 && \
    apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

RUN echo "shared_preload_libraries = 'pg_partman_bgw, pg_cron'" >> /usr/share/postgresql/postgresql.conf.sample && \
    echo "cron.database_name = 'warehouse'" >> /usr/share/postgresql/postgresql.conf.sample


COPY init-scripts/ /docker-entrypoint-initdb.d/