FROM twashing/simple-kafka-onyx-commander-base:latest

COPY . /app

ENTRYPOINT [ "lein" , "with-profile" , "+app" , "run" , "-m" , "com.interrupt.edgarly.core/-main" ]
