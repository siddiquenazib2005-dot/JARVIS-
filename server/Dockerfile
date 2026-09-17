FROM node:20-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY src ./src
COPY scripts ./scripts

ENV PORT=8080
ENV AURIX_DATA_DIR=/app/data
EXPOSE 8080

CMD ["node", "src/index.js"]
