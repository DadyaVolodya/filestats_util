## Сборка
```bash
mvn -q -DskipTests package
```
Fat-jar: `target/filestats-2.0.0-shaded.jar`

## Примеры
```bash
java -jar target/filestats-2.0.0-shaded.jar .

java -jar target/filestats-2.0.0-shaded.jar . --recursive --max-depth=3 --threads=8 --include-ext=java,sh --output=json

java -jar target/filestats-2.0.0-shaded.jar . --recursive --git-ignore --exclude-ext=png,jpg,jar,class --output=xml
```
