#!/bin/sh
mkdir -p /var/log/apache2
chmod o+rx /var/log/apache2
touch /var/log/apache2/access.log /var/log/apache2/error.log
chmod 644 /var/log/apache2/access.log /var/log/apache2/error.log
exec apache2ctl -D FOREGROUND
