FROM rasa/rasa:3.6.20-full

WORKDIR /app

USER root

COPY . /app
COPY docker/start-rasa.sh /usr/local/bin/start-rasa.sh

RUN chmod +x /usr/local/bin/start-rasa.sh

EXPOSE 5005

ENTRYPOINT []
CMD ["/usr/local/bin/start-rasa.sh"]
