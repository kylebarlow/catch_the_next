#!/bin/sh
mkdir -p /var/log/apache2
chmod o+rx /var/log/apache2
touch /var/log/apache2/access.log /var/log/apache2/error.log
chmod 644 /var/log/apache2/access.log /var/log/apache2/error.log

# /home/protected holds the SQLite caches (Transitland response cache and the
# 511 GTFS static/RT DBs). Apache writes as www-data, so ensure ownership.
mkdir -p /home/protected/gtfs511
chown -R www-data:www-data /home/protected
chmod 755 /home/protected /home/protected/gtfs511

exec apache2ctl -D FOREGROUND
