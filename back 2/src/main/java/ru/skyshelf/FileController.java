package ru.skyshelf;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api")
class FileController {
    private final StorageService storage;private final AuthController auth;
    FileController(StorageService s,AuthController a) {storage=s;auth=a;}

    record FilePatch(String name,String folderId,Boolean favorite,Boolean trashed) {}
    record FolderInput(String name,String color,String projectId) {}
    record ShareInput(Integer hours,String password,Integer maxDownloads) {}
    record PublicDownloadInput(String password) {}

    @GetMapping("/files")
    List<Map<String,Object>> files(Authentication a,@RequestParam(required=false) String projectId) {
        return storage.workspaceFiles(auth.current(a).id,blankToNull(projectId)).stream().map(StorageService::fileView).toList();
    }

    @GetMapping("/storage")
    Map<String,Object> usage(Authentication a,@RequestParam(required=false) String projectId) {
        String user=auth.current(a).id;String project=blankToNull(projectId);List<StoredFile> list=storage.workspaceFiles(user,project);
        Map<String,Object> out=new LinkedHashMap<>();out.put("plan",storage.plan(user,project));out.put("used",storage.used(user,project));out.put("quota",storage.limit(user,project));out.put("count",list.stream().filter(f->!f.trashed).count());out.put("trashCount",list.stream().filter(f->f.trashed).count());out.put("encryption","AES-256-GCM");return out;
    }

    @PostMapping("/files") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> upload(Authentication a,@RequestParam("file") MultipartFile file,@RequestParam(required=false) String folderId,@RequestParam(required=false) String projectId) throws Exception {
        return StorageService.fileView(storage.upload(auth.current(a).id,file.getOriginalFilename(),blankToNull(folderId),file.getBytes(),blankToNull(projectId)));
    }

    @PatchMapping("/files/{id}")
    Map<String,Object> update(Authentication a,@PathVariable String id,@RequestBody FilePatch p) {
        return StorageService.fileView(storage.update(id,auth.current(a).id,p.name(),p.folderId(),p.favorite(),p.trashed()));
    }

    @DeleteMapping("/files/{id}")
    Map<String,Object> trash(Authentication a,@PathVariable String id) {
        return StorageService.fileView(storage.update(id,auth.current(a).id,null,null,null,true));
    }

    @GetMapping("/files/{id}/download")
    ResponseEntity<ByteArrayResource> download(Authentication a,@PathVariable String id) throws Exception {
        StoredFile f=storage.owned(id,auth.current(a).id);if(f.trashed) throw StorageService.missing();return attachment(f.name,f.mimeType,storage.content(f));
    }

    @GetMapping("/files/{id}/versions")
    List<Map<String,Object>> versions(Authentication a,@PathVariable String id) {
        return storage.versionList(id,auth.current(a).id).stream().map(v->{
            Map<String,Object> out=new LinkedHashMap<>();out.put("id",v.id);out.put("version",v.versionNumber);out.put("size",v.size);out.put("checksum",v.checksum);out.put("note",v.note);out.put("createdAt",v.createdAt);out.put("createdById",v.createdById);out.put("author",storage.accountName(v.createdById));return out;
        }).toList();
    }

    @PostMapping("/files/{id}/versions") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> addVersion(Authentication a,@PathVariable String id,@RequestParam("file") MultipartFile file,@RequestParam(required=false) String note) throws Exception {
        return StorageService.fileView(storage.addVersion(id,auth.current(a).id,file.getOriginalFilename(),file.getBytes(),note));
    }

    @GetMapping("/files/{id}/versions/{versionId}/download")
    ResponseEntity<ByteArrayResource> versionDownload(Authentication a,@PathVariable String id,@PathVariable String versionId) throws Exception {
        StoredFile f=storage.owned(id,auth.current(a).id);FileVersion v=storage.version(id,versionId,auth.current(a).id);return attachment(versionName(f.name,v.versionNumber),v.mimeType,storage.versionContent(id,versionId,auth.current(a).id));
    }

    @PostMapping("/files/{id}/versions/{versionId}/restore")
    Map<String,Object> restoreVersion(Authentication a,@PathVariable String id,@PathVariable String versionId) throws Exception {
        return StorageService.fileView(storage.restoreVersion(id,versionId,auth.current(a).id));
    }

    @DeleteMapping("/files/{id}/permanent")
    Map<String,Boolean> purge(Authentication a,@PathVariable String id) throws Exception {storage.purge(id,auth.current(a).id);return Map.of("ok",true);}

    @GetMapping("/folders")
    List<Folder> folders(Authentication a,@RequestParam(required=false) String projectId) {return storage.folderList(auth.current(a).id,blankToNull(projectId));}
    @PostMapping("/folders") @ResponseStatus(HttpStatus.CREATED)
    Folder createFolder(Authentication a,@RequestBody FolderInput input) {return storage.createFolder(auth.current(a).id,input.name(),input.color(),blankToNull(input.projectId()));}
    @PatchMapping("/folders/{id}")
    Folder renameFolder(Authentication a,@PathVariable String id,@RequestBody FolderInput input) {return storage.updateFolder(id,auth.current(a).id,input.name(),input.color());}
    @DeleteMapping("/folders/{id}")
    Map<String,Boolean> deleteFolder(Authentication a,@PathVariable String id) {storage.deleteFolder(id,auth.current(a).id);return Map.of("ok",true);}

    @GetMapping("/files/{id}/share")
    List<Map<String,Object>> getShare(Authentication a,@PathVariable String id) {return storage.shareList(id,auth.current(a).id);}

    @PostMapping("/files/{id}/share")
    Map<String,Object> createShare(Authentication a,@PathVariable String id,@RequestBody ShareInput p) {
        int hours=p.hours()==null?24:p.hours();int max=p.maxDownloads()==null?0:p.maxDownloads();StorageService.CreatedShare share=storage.share(id,auth.current(a).id,hours,p.password(),max);
        Map<String,Object> out=new LinkedHashMap<>();out.put("token",share.token());out.put("id",share.id());out.put("expiresAt",share.expiresAt());out.put("passwordRequired",share.passwordRequired());out.put("maxDownloads",share.maxDownloads());out.put("downloadCount",share.downloadCount());return out;
    }

    @DeleteMapping("/files/{id}/share")
    Map<String,Boolean> revoke(Authentication a,@PathVariable String id) {storage.revoke(id,auth.current(a).id);return Map.of("ok",true);}

    @GetMapping("/public/{token}")
    Map<String,Object> publicInfo(@PathVariable String token) {
        StorageService.PublicFile publicFile=storage.publicInfo(token);StoredFile f=publicFile.file();ShareLink link=publicFile.link();Map<String,Object> out=new LinkedHashMap<>();
        out.put("name",f.name);out.put("size",f.size);out.put("mimeType",f.mimeType);out.put("updatedAt",f.updatedAt);out.put("passwordRequired",link.passwordHash!=null);out.put("expiresAt",link.expiresAt);out.put("maxDownloads",link.maxDownloads);out.put("downloadCount",link.downloadCount);out.put("remainingDownloads",link.maxDownloads==0?null:Math.max(0,link.maxDownloads-link.downloadCount));return out;
    }

    @GetMapping("/public/{token}/download")
    ResponseEntity<ByteArrayResource> publicDownload(@PathVariable String token) throws Exception {
        StorageService.PublicFile publicFile=storage.publicDownload(token,"");StoredFile f=publicFile.file();return attachment(f.name,f.mimeType,storage.content(f));
    }

    @PostMapping("/public/{token}/download")
    ResponseEntity<ByteArrayResource> publicPasswordDownload(@PathVariable String token,@RequestBody(required=false) PublicDownloadInput input) throws Exception {
        StorageService.PublicFile publicFile=storage.publicDownload(token,input==null?"":input.password());StoredFile f=publicFile.file();return attachment(f.name,f.mimeType,storage.content(f));
    }

    static ResponseEntity<ByteArrayResource> attachment(String name,String mime,byte[] bytes) {
        MediaType type;try{type=MediaType.parseMediaType(mime==null?"application/octet-stream":mime);}catch(Exception e){type=MediaType.APPLICATION_OCTET_STREAM;}
        return ResponseEntity.ok().contentType(type).contentLength(bytes.length)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").body(new ByteArrayResource(bytes));
    }

    private static String blankToNull(String value){return value==null||value.isBlank()?null:value;}
    private static String versionName(String name,int version){int dot=name.lastIndexOf('.');return dot<0?name+"-v"+version:name.substring(0,dot)+"-v"+version+name.substring(dot);}
}
