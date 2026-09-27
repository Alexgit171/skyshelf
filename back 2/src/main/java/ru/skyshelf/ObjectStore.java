package ru.skyshelf;

import java.io.*;
import java.nio.file.*;
import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

interface ObjectStore {
    void put(String key,byte[] bytes) throws Exception;
    InputStream get(String key) throws Exception;
    void delete(String key) throws Exception;
}

@Configuration
class StorageConfig {
    @Bean @ConditionalOnProperty(name="app.storage",havingValue="local",matchIfMissing=true)
    ObjectStore local(@Value("${app.storage-dir}") String dir) throws IOException {
        Path root=Path.of(dir).toAbsolutePath().normalize();java.nio.file.Files.createDirectories(root);
        return new ObjectStore() {
            private Path path(String key) {if(!key.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid object key");return root.resolve(key);}
            public void put(String key,byte[] bytes) throws IOException {
                Path tmp=java.nio.file.Files.createTempFile(root,"upload-",".tmp");
                try {java.nio.file.Files.write(tmp,bytes);java.nio.file.Files.move(tmp,path(key),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);} finally {java.nio.file.Files.deleteIfExists(tmp);}
            }
            public InputStream get(String key) throws IOException {return java.nio.file.Files.newInputStream(path(key));}
            public void delete(String key) throws IOException {java.nio.file.Files.deleteIfExists(path(key));}
        };
    }
    @Bean @ConditionalOnProperty(name="app.storage",havingValue="minio")
    ObjectStore minio(@Value("${app.minio.endpoint}") String endpoint,@Value("${app.minio.user}") String user,@Value("${app.minio.password}") String password,@Value("${app.minio.bucket}") String bucket) throws Exception {
        MinioClient client=MinioClient.builder().endpoint(endpoint).credentials(user,password).build();
        if(!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        return new ObjectStore() {
            public void put(String key,byte[] bytes) throws Exception {client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(bytes),bytes.length,-1).contentType("application/octet-stream").build());}
            public InputStream get(String key) throws Exception {return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());}
            public void delete(String key) throws Exception {client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());}
        };
    }
}
