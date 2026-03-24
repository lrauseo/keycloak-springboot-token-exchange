FROM alpine:3.19

RUN apk add --no-cache curl jq bash

COPY init-realms.sh /init-realms.sh
RUN chmod +x /init-realms.sh

CMD ["/init-realms.sh"]
