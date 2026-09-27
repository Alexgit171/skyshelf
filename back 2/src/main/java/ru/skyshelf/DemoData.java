package ru.skyshelf;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.annotation.Order;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Только при явном --app.demo=true. Никогда не использовать демопароли на публичном сервере. */
@Component @Order(10) @ConditionalOnProperty(name="app.demo",havingValue="true")
class DemoData implements ApplicationRunner {
    private final Accounts accounts;private final PasswordEncoder encoder;private final StorageService storage;private final Projects projects;
    DemoData(Accounts a,PasswordEncoder e,StorageService s,Projects p){accounts=a;encoder=e;storage=s;projects=p;}
    public void run(ApplicationArguments args) throws Exception {
        seed("demo@skyshelf.local","SkyShelfDemo2026!","Александр","USER");
        seed("admin@skyshelf.local","SkyShelfAdmin2026!","Администратор","ADMIN");
        seed("reader@skyshelf.local","SkyShelfDemo2026!","Сергей · читатель","USER");
        seed("team@skyshelf.local","SkyShelfDemo2026!","Лев · участник","USER");
        Account owner=accounts.findByEmail("demo@skyshelf.local").orElseThrow();
        if(projects.findAll().stream().noneMatch(p->p.ownerId.equals(owner.id))) {
            Project p=storage.projectService().create(owner.id,"SkyShelf · СПО","Создание программного обеспечения","Преподаватель СПО",Instant.now().plus(28,ChronoUnit.DAYS),"violet","aurora");
            storage.projectService().update(p.id,owner.id,null,"Защищённое облачное пространство команды ЭФБО-14-24: материалы, версии, роли, чек-лист сдачи и отдельная ссылка преподавателю.",null,null,p.deadline,null,null,null);
            storage.projectService().add(p.id,owner.id,"reader@skyshelf.local","READER");
            storage.projectService().add(p.id,owner.id,"team@skyshelf.local","MEMBER");
            StoredFile finalFile=storage.upload(owner.id,"SkyShelf · описание проекта.txt",null,"SkyShelf — защищённое облако для учебных команд.\n\nВозможности: роли, версии, избранное, чек-лист сдачи, итоговый файл, ссылка преподавателю и экспорт ZIP.\nЗащита: Argon2id, AES-256-GCM, SHA-256, TOTP и журнал действий.\n\nКоманда ЭФБО-14-24.\n".getBytes(StandardCharsets.UTF_8),p.id);
            storage.upload(owner.id,"Задание команды.txt",null,"Общее пространство команды ЭФБО-14-24.\nУчастник загружает материалы, читатель скачивает, владелец управляет доступом.\n".getBytes(StandardCharsets.UTF_8),p.id);
            storage.projectService().setFinal(p.id,owner.id,finalFile.id);
            var taskList=storage.projectService().taskRepository().findByProjectIdOrderByPositionAscCreatedAtAsc(p.id);for(int i=0;i<Math.min(2,taskList.size());i++)storage.projectService().updateTask(p.id,owner.id,taskList.get(i).id,null,true,null,taskList.get(i).dueAt);
        }
    }
    private void seed(String email,String password,String name,String role) throws Exception {
        if(accounts.findByEmail(email).isPresent()) return;
        Account a=new Account();a.email=email;a.name=name;a.passwordHash=encoder.encode(password);a.role=role;accounts.save(a);
        Folder study=storage.createFolder(a.id,"Учебные проекты","blue");storage.createFolder(a.id,"Личное","violet");storage.createFolder(a.id,"Материалы","mint");
        storage.upload(a.id,"Добро пожаловать.txt",null,("Привет! Это ваше пространство SkyShelf.\nЗагрузите файл, создайте папку, добавьте избранное, поделитесь ссылкой.\nУдалённые файлы можно восстановить из корзины.\nЭтот документ — настоящий файл, доступный для скачивания.\n").getBytes(StandardCharsets.UTF_8));
        storage.upload(a.id,"План проекта.md",study.id,("# SkyShelf / СПО\n\n1. Анализ требований и интерфейс\n2. REST API и личное хранилище\n3. Командные роли и версии файлов\n4. Тестирование, отчёт и защита\n\nЭФБО-14-24\nБухман Лев — Team Lead\nСелезнёв Сергей — Аналитик\nЛадинский Александр — Frontend/Backend\n").getBytes(StandardCharsets.UTF_8));
        StoredFile brief=storage.upload(a.id,"Описание SkyShelf.txt",study.id,"SkyShelf — облачное хранилище для файлов и небольших команд.\nСтек: HTML, CSS, JavaScript; Java + Spring Boot; PostgreSQL; MinIO.\nЛокальный деморежим: H2 и файловая система.\n".getBytes(StandardCharsets.UTF_8));
        storage.update(brief.id,a.id,null,null,true,null);
    }
}
