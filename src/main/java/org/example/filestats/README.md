# Сборка

### mvn -q -DskipTests package
артефакты: target/filestats-2.0.0.jar и target/filestats-2.0.0-shaded.jar

# Быстрый старт (Bash / PowerShell)

### 1) только текущий каталог, табличный вывод (plain)
java -jar target/filestats-2.0.0.jar .

### 2) то же, но c fat-jar
java -jar target/filestats-2.0.0-shaded.jar .

# Справка и версия

java -jar target/filestats-2.0.0.jar --help
java -jar target/filestats-2.0.0.jar --version

# Выбор формата вывода

### plain (по умолчанию)

java -jar target/filestats-2.0.0.jar . --output=plain

### JSON
java -jar target/filestats-2.0.0.jar . --output=json

### XML
java -jar target/filestats-2.0.0.jar . --output=xml

### рекурсивно на всю глубину
java -jar target/filestats-2.0.0.jar . --recursive

### рекурсивно с ограничением глубины (1 = только файлы непосредственно внутри <path>)
java -jar target/filestats-2.0.0.jar . --recursive --max-depth=1

### рекурсивно, глубина 3
java -jar target/filestats-2.0.0.jar . --recursive --max-depth=3

### авто (число ядер CPU) — по умолчанию
java -jar target/filestats-2.0.0.jar . --recursive

### явно 1 поток (для воспроизводимости)
java -jar target/filestats-2.0.0.jar . --recursive --threads=1

### 8 потоков
java -jar target/filestats-2.0.0.jar . --recursive --threads=8

### только указанные расширения (без точки, регистр неважен)
java -jar target/filestats-2.0.0.jar . --recursive --include-ext=java,sh

### исключить набор расширений
java -jar target/filestats-2.0.0.jar . --recursive --exclude-ext=png,jpg,jar,class

### совместное использование: include сначала сужает, exclude потом выкидывает
java -jar target/filestats-2.0.0.jar . --recursive --include-ext=java,sh --exclude-ext=sh

### учитывать правила из корневого <path>/.gitignore (упрощённая реализация)
java -jar target/filestats-2.0.0.jar . --recursive --git-ignore

### вместе с форматом
java -jar target/filestats-2.0.0.jar . --recursive --git-ignore --output=json

### JSON-отчёт по Java и Shell, на глубину 8, 8 потоков, с учётом .gitignore
java -jar target/filestats-2.0.0.jar . --recursive --max-depth=8 --threads=8 --include-ext=java,sh --git-ignore --output=json

### XML-отчёт, исключая бинарьё, на всю глубину
java -jar target/filestats-2.0.0.jar . --recursive --exclude-ext=png,jpg,gif,zip,jar,class --output=xml

### Plain-таблица только для текстовых: md, txt, xml; 1 поток
java -jar target/filestats-2.0.0.jar . --recursive --threads=1 --include-ext=md,txt,xml --output=plain




