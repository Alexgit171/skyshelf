package ru.skyshelf;

import java.util.*;
import java.time.Instant;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

@Service
class StorageService {
    final Files files;final Folders folders;final Shares shares;final Accounts accounts;final ObjectStore objects;
    final long quota;final ProjectService projects;final Versions versions;final CryptoService crypto;
    final PasswordEncoder encoder;final AuditService audit;

    StorageService(Files f,Folders d,Shares s,Accounts a,ObjectStore o,@Value("${app.quota-bytes}") long q,
        ProjectService p,Versions v,CryptoService c,PasswordEncoder e,AuditService audit) {
        files=f;folders=d;shares=s;accounts=a;objects=o;quota=q;projects=p;versions=v;crypto=c;encoder=e;this.audit=audit;
    }

    ProjectService projectService(){return projects;}
    ObjectStore objectStore(){return objects;}
    Shares shareRepository(){return shares;}
    Files fileRepository(){return files;}
    Versions versionRepository(){return versions;}
    String accountName(String id){return id==null?"Участник":accounts.findById(id).map(a->a.name).orElse("Участник");}
    static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Объект не найден или недоступен.");}

    static String name(String name,int max) {
        if(name==null) throw bad("Укажите название.");
        String clean=name.strip();
        if(clean.isBlank()||clean.length()>max||clean.equals(".")||clean.equals("..")||clean.matches(".*[\\p{Cntrl}/\\\\].*")) throw bad("Недопустимое название.");
        return clean;
    }

    void access(String actualOwner,String project,String actor,boolean write) {
        if(project!=null) projects.access(project,actor,write);else if(!actualOwner.equals(actor)) throw missing();
    }
    StoredFile owned(String id,String actor){StoredFile f=files.findById(id).orElseThrow(StorageService::missing);access(f.ownerId,f.projectId,actor,false);return f;}
    Folder folder(String id,String actor){Folder f=folders.findById(id).orElseThrow(StorageService::missing);access(f.ownerId,f.projectId,actor,false);return f;}

    List<StoredFile> list(String user) {
        Set<String> ids=new HashSet<>();projects.list(user).forEach(p->ids.add((String)p.get("id")));
        return files.findAll().stream().filter(f->f.projectId==null?f.ownerId.equals(user):ids.contains(f.projectId)).sorted(Comparator.comparing((StoredFile f)->f.updatedAt).reversed()).toList();
    }
    List<StoredFile> workspaceFiles(String user,String project){if(project!=null)projects.access(project,user,false);return list(user).stream().filter(f->Objects.equals(f.projectId,project)).toList();}
    List<Folder> folderList(String user,String project){if(project!=null)projects.access(project,user,false);return folders.findAll().stream().filter(f->Objects.equals(f.projectId,project)&&(project!=null||f.ownerId.equals(user))).sorted(Comparator.comparing(f->f.name)).toList();}

    long used(String user,String project) {
        return workspaceFiles(user,project).stream().mapToLong(f->{List<FileVersion> list=versions.findByFileIdOrderByVersionNumberDesc(f.id);return list.isEmpty()?f.size:list.stream().mapToLong(v->v.size).sum();}).sum();
    }
    String plan(String user,String project){return project==null?accounts.findById(user).orElseThrow(StorageService::missing).plan:projects.projectRepository().findById(project).orElseThrow(StorageService::missing).plan;}
    long limit(String user,String project){return switch(plan(user,project)){case "PERSONAL"->100L*1024*1024*1024;case "TEAM"->500L*1024*1024*1024;default->quota;};}
    void lock(String user,String project){if(project==null)accounts.lockById(user).orElseThrow(StorageService::missing);else{projects.projectRepository().lockById(project).orElseThrow(StorageService::missing);projects.access(project,user,true);}}

    static String ext(String name){int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    static String mime(String name){return switch(ext(name)){case "txt","md","csv"->"text/plain";case "pdf"->"application/pdf";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "zip"->"application/zip";case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "pptx"->"application/vnd.openxmlformats-officedocument.presentationml.presentation";default->"application/octet-stream";};}
    static void validateFile(String name,byte[] bytes) {
        String ext=ext(name);
        if(!Set.of("txt","md","csv","pdf","png","jpg","jpeg","zip","docx","xlsx","pptx").contains(ext)) throw bad("Разрешены TXT, MD, CSV, PDF, PNG, JPG, ZIP, DOCX, XLSX, PPTX.");
        if(bytes.length==0) throw bad("Пустой файл нельзя загрузить.");
        if(bytes.length>20*1024*1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Максимум 20 МБ на файл.");
        boolean valid=switch(ext){
            case "pdf"->bytes.length>=5&&new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-");
            case "png"->bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "jpg","jpeg"->bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255;
            case "zip","docx","xlsx","pptx"->bytes.length>=4&&bytes[0]==80&&bytes[1]==75&&(bytes[2]==3||bytes[2]==5||bytes[2]==7);
            default->{boolean text=true;for(byte b:bytes)if(b==0){text=false;break;}yield text;}
        };
        if(!valid) throw bad("Содержимое файла не соответствует расширению.");
    }

    private void putEncrypted(String key,byte[] plain) throws Exception {objects.put(key,crypto.encrypt(plain));}
    byte[] readObject(String key,String checksum) throws Exception {
        byte[] plain;try(InputStream in=objects.get(key)){plain=crypto.decrypt(in.readAllBytes());}
        if(checksum!=null&&!checksum.isBlank()&&!MessageDigestSafe.equals(checksum,CryptoService.checksum(plain))) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Проверка целостности файла не пройдена.");
        return plain;
    }
    byte[] content(StoredFile file) throws Exception{return readObject(file.objectKey,file.checksum);}

    @Transactional(rollbackFor=Exception.class)
    public StoredFile upload(String owner,String filename,String folderId,byte[] bytes) throws Exception{return upload(owner,filename,folderId,bytes,null);}
    @Transactional(rollbackFor=Exception.class)
    public StoredFile upload(String owner,String filename,String folderId,byte[] bytes,String project) throws Exception {
        lock(owner,project);String clean=name(filename,180);validateFile(clean,bytes);
        if(folderId!=null&&!folderId.isBlank()){if(!Objects.equals(folder(folderId,owner).projectId,project))throw bad("Папка находится в другом пространстве.");}else folderId=null;
        if(used(owner,project)+bytes.length>limit(owner,project))throw bad("Недостаточно места в хранилище.");
        StoredFile f=new StoredFile();f.ownerId=owner;f.createdById=owner;f.projectId=project;f.name=clean;f.folderId=folderId;f.size=bytes.length;f.mimeType=mime(clean);f.checksum=CryptoService.checksum(bytes);f.objectKey=UUID.randomUUID().toString();f.versionCount=1;
        putEncrypted(f.objectKey,bytes);cleanupOnRollback(f.objectKey);files.save(f);saveVersion(f,owner,"Первая версия",f.objectKey,bytes.length,f.checksum,f.mimeType,1);
        audit.record(owner,project,"FILE_UPLOADED","FILE",f.id,clean+" · SHA-256 "+f.checksum.substring(0,12));return f;
    }

    @Transactional(rollbackFor=Exception.class)
    public StoredFile addVersion(String id,String actor,String incomingName,byte[] bytes,String note) throws Exception {
        StoredFile f=owned(id,actor);lock(actor,f.projectId);access(f.ownerId,f.projectId,actor,true);
        if(f.trashed)throw bad("Сначала восстановите файл из корзины.");
        String clean=name(incomingName,180);if(!ext(clean).equals(ext(f.name)))throw bad("Новая версия должна иметь то же расширение.");validateFile(clean,bytes);
        if(used(actor,f.projectId)+bytes.length>limit(actor,f.projectId))throw bad("Недостаточно места: версии файлов учитываются в квоте.");
        ensureVersionRow(f);int number=Math.max(1,f.versionCount)+1;String key=UUID.randomUUID().toString();String sum=CryptoService.checksum(bytes);
        putEncrypted(key,bytes);cleanupOnRollback(key);saveVersion(f,actor,note,key,bytes.length,sum,mime(clean),number);
        f.objectKey=key;f.size=bytes.length;f.checksum=sum;f.mimeType=mime(clean);f.versionCount=number;f.updatedAt=Instant.now();files.save(f);
        audit.record(actor,f.projectId,"FILE_VERSION_ADDED","FILE",f.id,f.name+" · версия "+number);return f;
    }

    private FileVersion saveVersion(StoredFile f,String actor,String note,String key,long size,String sum,String mime,int number) {
        FileVersion v=new FileVersion();v.fileId=f.id;v.createdById=actor;String clean=note==null?"":note.strip();v.note=clean.substring(0,Math.min(240,clean.length()));v.objectKey=key;v.size=size;v.checksum=sum;v.mimeType=mime;v.versionNumber=number;return versions.save(v);
    }
    private void ensureVersionRow(StoredFile f) {
        if(!versions.findByFileIdOrderByVersionNumberDesc(f.id).isEmpty())return;
        saveVersion(f,f.createdById==null?f.ownerId:f.createdById,"Импортированная первая версия",f.objectKey,f.size,f.checksum,f.mimeType==null?mime(f.name):f.mimeType,1);f.versionCount=1;
    }
    private void cleanupOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{objects.delete(key);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(StorageService.class).error("Orphan object {}",key,e);}}});
    }

    List<FileVersion> versionList(String id,String actor){StoredFile f=owned(id,actor);ensureVersionRow(f);return versions.findByFileIdOrderByVersionNumberDesc(id);}
    FileVersion version(String fileId,String versionId,String actor){owned(fileId,actor);return versions.findByIdAndFileId(versionId,fileId).orElseThrow(StorageService::missing);}
    byte[] versionContent(String fileId,String versionId,String actor) throws Exception {FileVersion v=version(fileId,versionId,actor);return readObject(v.objectKey,v.checksum);}

    @Transactional(rollbackFor=Exception.class)
    public StoredFile restoreVersion(String fileId,String versionId,String actor) throws Exception {
        FileVersion v=version(fileId,versionId,actor);byte[] bytes=readObject(v.objectKey,v.checksum);return addVersion(fileId,actor,owned(fileId,actor).name,bytes,"Восстановлена версия "+v.versionNumber);
    }

    @Transactional
    public StoredFile update(String id,String owner,String newName,String targetFolder,Boolean favorite,Boolean trashed) {
        StoredFile f=owned(id,owner);lock(owner,f.projectId);access(f.ownerId,f.projectId,owner,true);
        if(f.trashed&&!Boolean.FALSE.equals(trashed))throw bad("Сначала восстановите файл из корзины.");
        if(newName!=null){String clean=name(newName,180);if(!ext(clean).equals(ext(f.name)))throw bad("При переименовании сохраните расширение файла.");f.name=clean;f.mimeType=mime(clean);}
        if(targetFolder!=null){if(targetFolder.isBlank())f.folderId=null;else{if(!Objects.equals(folder(targetFolder,owner).projectId,f.projectId))throw bad("Папка находится в другом пространстве.");f.folderId=targetFolder;}}
        if(favorite!=null)f.favorite=favorite;
        if(trashed!=null){f.trashed=trashed;if(trashed)shares.deleteByFileId(f.id);audit.record(owner,f.projectId,trashed?"FILE_TRASHED":"FILE_RESTORED","FILE",f.id,f.name);}
        f.updatedAt=Instant.now();return files.save(f);
    }

    @Transactional public Folder createFolder(String owner,String folderName,String color){return createFolder(owner,folderName,color,null);}
    @Transactional public Folder createFolder(String owner,String folderName,String color,String project){
        lock(owner,project);String clean=name(folderName,120);validColor(color);
        if(folderList(owner,project).stream().anyMatch(d->d.name.equalsIgnoreCase(clean)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Папка с таким названием уже существует.");
        Folder d=new Folder();d.ownerId=owner;d.projectId=project;d.name=clean;d.color=color;folders.save(d);audit.record(owner,project,"FOLDER_CREATED","FOLDER",d.id,clean);return d;
    }
    @Transactional public Folder updateFolder(String id,String owner,String newName,String color){
        Folder d=folder(id,owner);lock(owner,d.projectId);access(d.ownerId,d.projectId,owner,true);String clean=name(newName,120);
        if(folderList(owner,d.projectId).stream().anyMatch(x->!x.id.equals(id)&&x.name.equalsIgnoreCase(clean)))throw bad("Такая папка уже существует.");
        d.name=clean;validColor(color);d.color=color;return folders.save(d);
    }
    static void validColor(String color){if(color==null||!Set.of("blue","violet","mint","amber","cyan","rose").contains(color))throw bad("Выберите цвет папки.");}
    @Transactional public void deleteFolder(String id,String owner){Folder d=folder(id,owner);lock(owner,d.projectId);access(d.ownerId,d.projectId,owner,true);if(files.existsByFolderId(id))throw bad("Папка не пуста. Переместите файлы, в том числе из корзины, в другую папку.");folders.delete(d);audit.record(owner,d.projectId,"FOLDER_DELETED","FOLDER",id,d.name);}

    record CreatedShare(String token,String id,Instant expiresAt,boolean passwordRequired,int maxDownloads,int downloadCount){}
    @Transactional
    public CreatedShare share(String id,String owner,int hours,String password,int maxDownloads) {
        StoredFile f=owned(id,owner);if(f.trashed)throw bad("Файл в корзине.");lock(owner,f.projectId);if(f.projectId!=null)projects.owner(f.projectId,owner);
        if(hours<1||hours>720)throw bad("Срок ссылки — от 1 до 720 часов.");if(maxDownloads<0||maxDownloads>1000)throw bad("Лимит скачиваний — от 0 до 1000.");
        String pass=password==null?"":password.strip();if(!pass.isEmpty()&&(pass.length()<4||pass.length()>64))throw bad("Пароль ссылки: от 4 до 64 символов.");
        shares.deleteByFileId(id);String raw=crypto.newToken();ShareLink s=new ShareLink();s.token=CryptoService.tokenHash(raw);s.fileId=id;s.expiresAt=Instant.now().plusSeconds(hours*3600L);s.maxDownloads=maxDownloads;s.passwordHash=pass.isEmpty()?null:encoder.encode(pass);shares.save(s);
        audit.record(owner,f.projectId,"SHARE_CREATED","FILE",f.id,"Срок "+hours+" ч., лимит "+(maxDownloads==0?"без ограничений":maxDownloads));return new CreatedShare(raw,s.token,s.expiresAt,s.passwordHash!=null,s.maxDownloads,s.downloadCount);
    }
    List<Map<String,Object>> shareList(String id,String owner){StoredFile f=owned(id,owner);if(f.projectId!=null)projects.owner(f.projectId,owner);return shares.findByFileId(id).stream().map(StorageService::shareView).toList();}
    static Map<String,Object> shareView(ShareLink s){Map<String,Object> m=new LinkedHashMap<>();m.put("id",s.token);m.put("createdAt",s.createdAt);m.put("expiresAt",s.expiresAt);m.put("passwordRequired",s.passwordHash!=null);m.put("maxDownloads",s.maxDownloads);m.put("downloadCount",s.downloadCount);return m;}
    @Transactional public void revoke(String id,String owner){StoredFile f=owned(id,owner);lock(owner,f.projectId);if(f.projectId!=null)projects.owner(f.projectId,owner);shares.deleteByFileId(id);audit.record(owner,f.projectId,"SHARE_REVOKED","FILE",id,f.name);}

    @Transactional(rollbackFor=Exception.class)
    public void purge(String id,String actor) throws Exception {
        StoredFile f=owned(id,actor);lock(actor,f.projectId);if(f.projectId!=null)projects.owner(f.projectId,actor);if(!f.trashed)throw bad("Сначала переместите файл в корзину.");
        shares.deleteByFileId(id);List<String> keys=new ArrayList<>(versions.findByFileIdOrderByVersionNumberDesc(id).stream().map(v->v.objectKey).toList());keys.add(f.objectKey);versions.deleteByFileId(id);files.delete(f);projects.clearFinalIf(f.projectId,f.id);audit.record(actor,f.projectId,"FILE_PURGED","FILE",id,f.name);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){keys.stream().distinct().forEach(k->{try{objects.delete(k);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(StorageService.class).error("Object cleanup required {}",k,e);}});}});
    }

    record PublicFile(StoredFile file,ShareLink link){}
    PublicFile publicInfo(String rawToken){ShareLink link=publicLink(rawToken,false,null);StoredFile f=availableSharedFile(link);return new PublicFile(f,link);}
    @Transactional public PublicFile publicDownload(String rawToken,String password){ShareLink link=publicLink(rawToken,true,password);StoredFile f=availableSharedFile(link);link.downloadCount++;shares.save(link);return new PublicFile(f,link);}
    private ShareLink publicLink(String raw,boolean download,String password){
        String hash=CryptoService.tokenHash(raw==null?"":raw);ShareLink link=(download?shares.lockByToken(hash):shares.findById(hash)).filter(s->s.expiresAt.isAfter(Instant.now())).orElseThrow(StorageService::missing);
        if(link.maxDownloads>0&&link.downloadCount>=link.maxDownloads)throw missing();
        if(download&&link.passwordHash!=null&&!encoder.matches(password==null?"":password,link.passwordHash))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Неверный пароль ссылки.");return link;
    }
    private StoredFile availableSharedFile(ShareLink link){
        StoredFile f=files.findById(link.fileId).filter(x->!x.trashed).orElseThrow(StorageService::missing);String owner=f.projectId==null?f.ownerId:projects.projectRepository().findById(f.projectId).orElseThrow(StorageService::missing).ownerId;
        if(accounts.findById(owner).map(a->a.blocked).orElse(true))throw missing();return f;
    }
    StoredFile publicFile(String token){return publicInfo(token).file();}

    @Transactional(rollbackFor=Exception.class)
    public void migrateExisting() throws Exception {
        for(StoredFile f:files.findAll()){
            byte[] stored;try(InputStream in=objects.get(f.objectKey)){stored=in.readAllBytes();}catch(FileNotFoundException e){continue;}
            byte[] plain=crypto.decrypt(stored);boolean changed=false;
            if(!crypto.isEncrypted(stored)){objects.put(f.objectKey,crypto.encrypt(plain));changed=true;}
            if(f.checksum==null||f.checksum.isBlank()){f.checksum=CryptoService.checksum(plain);changed=true;}
            if(f.mimeType==null||f.mimeType.isBlank()){f.mimeType=mime(f.name);changed=true;}
            if(f.createdById==null){f.createdById=f.ownerId;changed=true;}
            if(f.versionCount<1){f.versionCount=1;changed=true;}
            if(changed)files.save(f);ensureVersionRow(f);
        }
    }

    static Map<String,Object> fileView(StoredFile f){Map<String,Object> m=new LinkedHashMap<>();m.put("id",f.id);m.put("projectId",f.projectId);m.put("name",f.name);m.put("size",f.size);m.put("mimeType",f.mimeType);m.put("checksum",f.checksum);m.put("versionCount",Math.max(1,f.versionCount));m.put("folderId",f.folderId);m.put("favorite",f.favorite);m.put("trashed",f.trashed);m.put("createdAt",f.createdAt);m.put("updatedAt",f.updatedAt);return m;}

    private static final class MessageDigestSafe {static boolean equals(String a,String b){return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}}
}
