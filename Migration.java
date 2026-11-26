package ru.rpcore.db;

import java.util.List;

/**
 * Одна версия схемы модуля. Версии применяются по возрастанию, каждая - один раз.
 * Уже применённую миграцию никогда не редактируйте - добавляйте новую версию.
 */
public record Migration(int version, List<String> statements) {

    public static Migration of(int version, String... statements) {
        return new Migration(version, List.of(statements));
    }
}
