FROM twashing/simple-kafka-onyx-commander-base:latest

COPY . /app

ENTRYPOINT [ "lein" , "run" , "-m" , "com.interrupt.edgarly.core/-main" ]
