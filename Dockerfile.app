FROM pandeiro/lein:latest

COPY project.clj .
COPY . /app
RUN lein deps

ENTRYPOINT [ "lein" , "with-profile" , "+app" , "run" , "-m" , "com.interrupt.edgarly.core/-main" ]
