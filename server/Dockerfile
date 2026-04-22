FROM python:3.12-slim

RUN apt-get update && \
    apt-get install -y apache2 apache2-dev gcc && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir mod_wsgi -r /tmp/requirements.txt

RUN mod_wsgi-express install-module > /etc/apache2/mods-available/wsgi.load && \
    a2enmod wsgi rewrite

COPY apache/site.conf /etc/apache2/sites-available/ctn.conf
RUN a2ensite ctn && a2dissite 000-default

COPY . /app

RUN chmod +x /app/docker-entrypoint.sh

EXPOSE 80
CMD ["/app/docker-entrypoint.sh"]
