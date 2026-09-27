package ru.skyshelf;

import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Обновляет объекты старого формата: шифрует их и создаёт первую запись истории версий. */
@Component @Order(20)
class CryptoMigration implements ApplicationRunner {
    private final StorageService storage;
    CryptoMigration(StorageService storage){this.storage=storage;}
    public void run(ApplicationArguments args) throws Exception {storage.migrateExisting();}
}
