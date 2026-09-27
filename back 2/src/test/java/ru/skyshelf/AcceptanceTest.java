package ru.skyshelf;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:acceptance;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","spring.jpa.hibernate.ddl-auto=create-drop","app.demo=false","app.quota-bytes=1024"})
@AutoConfigureMockMvc
class AcceptanceTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired Accounts accounts;@Autowired PasswordEncoder encoder;
    @Autowired StorageService storage;@Autowired ProjectService projects;@Autowired Shares shares;@Autowired ObjectStore objects;
    @Autowired CryptoService crypto;@Autowired TotpService totp;
    private Account alice,bob,admin;
    @DynamicPropertySource static void storageDirectory(DynamicPropertyRegistry r) throws Exception {String dir=java.nio.file.Files.createTempDirectory("skyshelf-test-").toString();r.add("app.storage-dir",()->dir);}
    @BeforeEach void accounts(){alice=account("Alice","USER");bob=account("Bob","USER");admin=account("Admin","ADMIN");}
    Account account(String name,String role){Account a=new Account();a.name=name;a.email=UUID.randomUUID()+"@example.com";a.role=role;a.passwordHash=encoder.encode("StrongPassword2026!");return accounts.save(a);}
    JsonNode data(MvcResult r) throws Exception{return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    String body(Object object) throws Exception{return json.writeValueAsString(object);}
    StoredFile file(Account a) throws Exception{return storage.upload(a.id,"hello.txt",null,"hello".getBytes());}
    @Test void guestCannotListFilesOrAdmin() throws Exception {mvc.perform(get("/api/files")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());}
    @Test void csrfIsRequiredForMutations() throws Exception {mvc.perform(post("/api/folders").with(user(alice.email)).contentType("application/json").content("{\"name\":\"Folder\",\"color\":\"blue\"}")).andExpect(status().isForbidden());}
    @Test void registrationSessionLoginAndLogout() throws Exception {
        String email="new-"+UUID.randomUUID()+"@example.com";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content(body(Map.of("email",email,"password","StrongPassword2026!","name","Новый пользователь")))).andExpect(status().isCreated());
        Account a=accounts.findByEmail(email).orElseThrow();assertTrue(a.passwordHash.startsWith("$argon2id$"));assertFalse(a.passwordHash.contains("StrongPassword"));
        MvcResult login=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(body(Map.of("email",email,"password","StrongPassword2026!")))).andExpect(status().isOk()).andReturn();
        MockHttpSession session=(MockHttpSession)login.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk());assertTrue(session.isInvalid());
    }
    @Test void uploadDownloadRenameMoveTrashRestoreAndPurge() throws Exception {
        MockMultipartFile upload=new MockMultipartFile("file","notes.txt","text/plain","exact uploaded bytes".getBytes());
        String id=data(mvc.perform(multipart("/api/files").file(upload).with(user(alice.email)).with(csrf())).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(get("/api/files/"+id+"/download").with(user(alice.email))).andExpect(status().isOk()).andExpect(content().bytes(upload.getBytes()));
        Folder folder=storage.createFolder(alice.id,"Course","blue");
        mvc.perform(patch("/api/files/"+id).with(user(alice.email)).with(csrf()).contentType("application/json").content(body(Map.of("name","renamed.txt","folderId",folder.id)))).andExpect(status().isOk()).andExpect(jsonPath("$.folderId").value(folder.id));
        mvc.perform(delete("/api/files/"+id).with(user(alice.email)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/files/"+id+"/download").with(user(alice.email))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/files/"+id).with(user(alice.email)).with(csrf()).contentType("application/json").content("{\"trashed\":false}")).andExpect(status().isOk());
        assertFalse(storage.owned(id,alice.id).trashed);
        storage.update(id,alice.id,null,null,null,true);storage.purge(id,alice.id);assertFalse(storage.fileRepository().existsById(id));
    }
    @Test void otherUserAndAdminCannotReadPrivateFile() throws Exception {
        StoredFile f=file(alice);
        mvc.perform(get("/api/files/"+f.id+"/download").with(user(bob.email))).andExpect(status().isNotFound());
        mvc.perform(get("/api/files/"+f.id+"/download").with(user(admin.email).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/files/"+f.id).with(user(bob.email)).with(csrf()).contentType("application/json").content("{\"name\":\"stolen.txt\"}")).andExpect(status().isNotFound());
    }
    @Test void readersCannotWriteMembersCanAndRemovalRevokesAccess() throws Exception {
        Project p=projects.create(alice.id,"Group");projects.add(p.id,alice.id,bob.email,"READER");
        StoredFile f=storage.upload(alice.id,"team.txt",null,"team".getBytes(),p.id);
        mvc.perform(get("/api/files/"+f.id+"/download").with(user(bob.email))).andExpect(status().isOk());
        mvc.perform(delete("/api/files/"+f.id).with(user(bob.email)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file","new.txt","text/plain","new".getBytes())).param("projectId",p.id).with(user(bob.email)).with(csrf())).andExpect(status().isForbidden());
        Membership m=projects.memberRepository().findByProjectIdAndUserId(p.id,bob.id).orElseThrow();projects.change(p.id,alice.id,m.id,"MEMBER",false);
        mvc.perform(patch("/api/files/"+f.id).with(user(bob.email)).with(csrf()).contentType("application/json").content("{\"name\":\"team-new.txt\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/files/"+f.id+"/share").with(user(bob.email)).with(csrf()).contentType("application/json").content("{\"hours\":24}")).andExpect(status().isForbidden());
        projects.change(p.id,alice.id,m.id,null,true);
        mvc.perform(get("/api/files/"+f.id+"/download").with(user(bob.email))).andExpect(status().isNotFound());
    }
    @Test void sharedLinksExpireRevokeAndTrashDoesNotResurrectLinks() throws Exception {
        StoredFile f=file(alice);StorageService.CreatedShare created=storage.share(f.id,alice.id,1,"",0);ShareLink link=shares.findById(created.id()).orElseThrow();
        mvc.perform(get("/api/public/"+created.token()+"/download")).andExpect(status().isOk());
        link.expiresAt=java.time.Instant.now().minusSeconds(1);shares.save(link);
        mvc.perform(get("/api/public/"+created.token())).andExpect(status().isNotFound());
        created=storage.share(f.id,alice.id,24,"",0);storage.revoke(f.id,alice.id);
        mvc.perform(get("/api/public/"+created.token())).andExpect(status().isNotFound());
        created=storage.share(f.id,alice.id,24,"",0);storage.update(f.id,alice.id,null,null,null,true);storage.update(f.id,alice.id,null,null,null,false);
        mvc.perform(get("/api/public/"+created.token())).andExpect(status().isNotFound());
    }
    @Test void blockedUserExistingSessionAndLinksDenied() throws Exception {
        StoredFile f=file(alice);StorageService.CreatedShare s=storage.share(f.id,alice.id,24,"",0);
        mvc.perform(patch("/api/admin/users/"+alice.id).with(user(admin.email).roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"blocked\":true}")).andExpect(status().isOk());
        mvc.perform(get("/api/me").with(user(alice.email))).andExpect(status().isForbidden());
        mvc.perform(get("/api/public/"+s.token())).andExpect(status().isNotFound());
    }

    @Test void objectsAreEncryptedAndIntegrityChecked() throws Exception {
        byte[] plain="top secret student project".getBytes();StoredFile f=storage.upload(alice.id,"secret.txt",null,plain);
        byte[] stored;try(var input=objects.get(f.objectKey)){stored=input.readAllBytes();}
        assertFalse(Arrays.equals(plain,stored));assertArrayEquals(new byte[]{'S','K','Y','2'},Arrays.copyOf(stored,4));assertArrayEquals(plain,storage.content(f));assertEquals(CryptoService.checksum(plain),f.checksum);
    }

    @Test void versionsCanBeAddedDownloadedAndRestored() throws Exception {
        StoredFile f=file(alice);storage.addVersion(f.id,alice.id,"hello.txt","second".getBytes(),"Правки");List<FileVersion> history=storage.versionList(f.id,alice.id);assertEquals(2,history.size());assertEquals("second",new String(storage.content(f)));
        storage.restoreVersion(f.id,history.get(1).id,alice.id);assertEquals("hello",new String(storage.content(f)));assertEquals(3,storage.versionList(f.id,alice.id).size());
    }

    @Test void protectedShareStoresOnlyHashAndEnforcesLimit() throws Exception {
        StoredFile f=file(alice);StorageService.CreatedShare created=storage.share(f.id,alice.id,24,"pass-2026",1);assertNotEquals(created.token(),created.id());assertFalse(shares.existsById(created.token()));
        mvc.perform(post("/api/public/"+created.token()+"/download").with(csrf()).contentType("application/json").content("{\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/"+created.token()+"/download").with(csrf()).contentType("application/json").content("{\"password\":\"pass-2026\"}")).andExpect(status().isOk()).andExpect(content().bytes("hello".getBytes()));
        mvc.perform(get("/api/public/"+created.token())).andExpect(status().isNotFound());
    }

    @Test void projectChecklistFinalAndTeacherSubmissionWork() throws Exception {
        Project p=projects.create(alice.id,"Course project","СПО","Преподаватель",java.time.Instant.now().plusSeconds(86400),"violet","aurora");StoredFile f=storage.upload(alice.id,"final.txt",null,"result".getBytes(),p.id);projects.setFinal(p.id,alice.id,f.id);assertEquals(4,projects.taskList(p.id,alice.id).size());
        ProjectService.CreatedSubmission link=projects.createSubmission(p.id,alice.id,48);ProjectService.PublicSubmission publicView=projects.publicSubmission(link.token(),true);assertEquals(f.id,publicView.file().id);assertEquals(1,publicView.link().views);
    }

    @Test void totpSecondFactorIsRequiredAfterPassword() throws Exception {
        String secret=totp.newSecret();alice.twoFactorSecret=crypto.encryptText(secret);alice.twoFactorEnabled=true;accounts.save(alice);String code=totp.code(secret,java.time.Instant.now().getEpochSecond()/30);
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(body(Map.of("email",alice.email,"password","StrongPassword2026!")))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(body(Map.of("email",alice.email,"password","StrongPassword2026!","code",code)))).andExpect(status().isOk()).andExpect(jsonPath("$.twoFactorEnabled").value(true));
    }
    @Test void adminRouteRequiresAdminAndCanAssignPlan() throws Exception {
        mvc.perform(get("/api/admin/users").with(user(alice.email))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/"+alice.id+"/plan").with(user(admin.email).roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"plan\":\"PERSONAL\"}")).andExpect(status().isOk());
        assertEquals(100L*1024*1024*1024,storage.limit(alice.id,null));
    }
    @Test void uploadValidationAndPathTraversal() throws Exception {
        for(String name:List.of("../evil.txt","evil.exe","fake.pdf"))
            mvc.perform(multipart("/api/files").file(new MockMultipartFile("file",name,"text/plain","invalid".getBytes())).with(user(alice.email)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/files").file(new MockMultipartFile("file","empty.txt","text/plain",new byte[0])).with(user(alice.email)).with(csrf())).andExpect(status().isBadRequest());
    }
    @Test void cannotMoveToForeignFolderOrOtherWorkspace() throws Exception {
        StoredFile f=file(alice);Folder b=storage.createFolder(bob.id,"Private","blue");
        mvc.perform(patch("/api/files/"+f.id).with(user(alice.email)).with(csrf()).contentType("application/json").content(body(Map.of("folderId",b.id)))).andExpect(status().isNotFound());
        Project p=projects.create(alice.id,"Project");Folder d=storage.createFolder(alice.id,"Team folder","blue",p.id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->storage.update(f.id,alice.id,null,d.id,null,null));
    }
    @Test void projectLimitIncludesOwner() {
        Project p=projects.create(alice.id,"Five");
        for(int i=0;i<4;i++){Account a=account("member","USER");projects.add(p.id,alice.id,a.email,"MEMBER");}
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->projects.add(p.id,alice.id,bob.email,"MEMBER"));
        assertEquals(5,projects.memberRepository().findByProjectId(p.id).size());
    }
    @Test void simultaneousUploadsCannotExceedPersonalQuota() throws Exception {assertConcurrentQuota(null);}
    @Test void simultaneousUploadsCannotExceedSharedQuota() throws Exception {assertConcurrentQuota(projects.create(alice.id,"Quota").id);}
    void assertConcurrentQuota(String projectId) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
        byte[] bytes=new byte[700];Arrays.fill(bytes,(byte)'a');
        for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();try{storage.upload(alice.id,UUID.randomUUID()+".txt",null,bytes,projectId);return true;}catch(org.springframework.web.server.ResponseStatusException e){assertEquals(400,e.getStatusCode().value());return false;}}));
        start.countDown();int passed=0;try{for(Future<Boolean> f:futures)if(f.get(20,TimeUnit.SECONDS))passed++;}finally{pool.shutdownNow();}
        assertEquals(1,passed);assertEquals(700,storage.used(alice.id,projectId));
    }
    @Test void trashCountsAgainstQuotaUntilPermanentDeletion() throws Exception {
        byte[] bytes=new byte[700];Arrays.fill(bytes,(byte)'a');StoredFile f=storage.upload(alice.id,"big.txt",null,bytes);
        storage.update(f.id,alice.id,null,null,null,true);assertEquals(700,storage.used(alice.id,null));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->storage.upload(alice.id,"second.txt",null,bytes));
        storage.purge(f.id,alice.id);assertEquals(0,storage.used(alice.id,null));storage.upload(alice.id,"second.txt",null,bytes);
    }
    @Test void repeatedBadLoginsAreLimited() throws Exception {
        for(int i=0;i<10;i++)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(body(Map.of("email",alice.email,"password","wrong")))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(body(Map.of("email",alice.email,"password","wrong")))).andExpect(status().isTooManyRequests());
    }
}
