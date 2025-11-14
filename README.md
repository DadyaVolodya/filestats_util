# FileStats v2 (Picocli + Jackson)

Консольная утилита с «правильным» разбором аргументов (Picocli) и сериализацией вывода (Jackson JSON/XML).

## Сборка
```bash
mvn -q -DskipTests package
```
Fat-jar: `target/filestats-2.0.0-shaded.jar`

## Примеры
```bash
# plain
java -jar target/filestats-2.0.0-shaded.jar .

# рекурсивно до 3 уровней, только .java и .sh, вывод JSON
java -jar target/filestats-2.0.0-shaded.jar . --recursive --max-depth=3 --threads=8 --include-ext=java,sh --output=json

# c упрощённым .gitignore и XML
java -jar target/filestats-2.0.0-shaded.jar . --recursive --git-ignore --exclude-ext=png,jpg,jar,class --output=xml
```
