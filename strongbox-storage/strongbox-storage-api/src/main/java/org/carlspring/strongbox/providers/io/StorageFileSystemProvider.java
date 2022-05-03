package org.carlspring.strongbox.providers.io;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.io.output.ProxyOutputStream;
import org.carlspring.strongbox.storage.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;

/**
 * This class decorates storage {@link FileSystemProvider}.
 * <br>
 * Note that almost all {@link Path} operations in this implementation must
 * delegate invocations into {@link Files} utility class with {@link RepositoryPath}'s target as parameters.
 *
 * TODO: we need a proper implementation against Service Provider Interface (SPI) specification
 *
 * @author Sergey Bespalov
 */
public abstract class StorageFileSystemProvider
        extends FileSystemProvider
{

    public static final String STRONGBOX_SCHEME = "strongbox";

    private static final Logger logger = LoggerFactory.getLogger(StorageFileSystemProvider.class);

    private FileSystemProvider target;

    public StorageFileSystemProvider(FileSystemProvider target)
    {
        super();
        this.target = target;
    }

    public String getScheme()
    {
        return STRONGBOX_SCHEME;
    }

    public FileSystem newFileSystem(URI uri,
                                    Map<String, ?> env)
        throws IOException
    {
        throw new UnsupportedOperationException();
    }

    public FileSystem getFileSystem(URI uri)
    {
        throw new UnsupportedOperationException();
    }

    public Path getPath(URI uri)
    {
        throw new UnsupportedOperationException();
    }

    public SeekableByteChannel newByteChannel(Path path,
                                              Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs)
        throws IOException
    {
        return getTarget().newByteChannel(unwrap(path), options, attrs);
    }
    
    @Override
    public FileChannel newFileChannel(Path path,
                                      Set<? extends OpenOption> options,
                                      FileAttribute<?>... attrs)
        throws IOException
    {
        return getTarget().newFileChannel(unwrap(path), options, attrs);
    }

    public DirectoryStream<Path> newDirectoryStream(Path dir,
                                                    Filter<? super Path> filter)
        throws IOException
    {
        RepositoryPath repositoryPath = (RepositoryPath) dir;
        Path root = repositoryPath.getFileSystem().getRootDirectory();
        DirectoryStream<Path> directoryStream = getTarget().newDirectoryStream(unwrap(dir), new LayoutDirectoryStreamFilter(unwrap(root), filter));
        
        return new DirectoryStream<Path>()
        {

            @Override
            public void close()
                throws IOException
            {
                directoryStream.close();
            }

            @Override
            public Iterator<Path> iterator()
            {
                return createRepositoryDsIterator(repositoryPath.getFileSystem(), directoryStream.iterator());
            }

        };
    }

    protected Iterator<Path> createRepositoryDsIterator(LayoutFileSystem rfs,
                                                        Iterator<Path> iterator)
    {

        return new Iterator<Path>()
        {

            @Override
            public boolean hasNext()
            {
                return iterator.hasNext();
            }

            @Override
            public Path next()
            {
                return new RepositoryPath(iterator.next(), rfs);
            }

            @Override
            public void remove()
            {
                iterator.remove();
            }

            @Override
            public void forEachRemaining(Consumer<? super Path> action)
            {
                iterator.forEachRemaining(action);
            }

        };
    }

    public void createDirectory(Path dir,
                                FileAttribute<?>... attrs)
        throws IOException
    {
        getTarget().createDirectory(unwrap(dir), attrs);
    }

    public void delete(Path path)
        throws IOException
    {
        delete(path, false);
    }

    public void delete(Path path,
                       boolean force)
        throws IOException
    {
        if (!(path instanceof RepositoryPath))
        {
            Files.delete(path);
            
            return;
        }

        RepositoryPath repositoryPath = (RepositoryPath) path;
        if (!Files.exists(repositoryPath.getTarget()))
        {
            throw new NoSuchFileException(unwrap(repositoryPath).toString());
        }

        if (!Files.isDirectory(repositoryPath))
        {
            doDeletePath(repositoryPath, force, true);

            return;
        }

        RootRepositoryPath root = repositoryPath.getFileSystem().getRootDirectory();
        recursiveDeleteExceptRoot(repositoryPath, force);
        if (!root.equals(path))
        {
            return;
        }
        
        logger.debug("Deleting hidden folders for [{}]", path);
        
        FileSystemUtils.deleteRecursively(unwrap(root).resolve(LayoutFileSystem.TEMP));
        FileSystemUtils.deleteRecursively(unwrap(root).resolve(LayoutFileSystem.TRASH));
        Files.delete(unwrap(root));
        
        logger.debug("Hidden folders deleted [{}]", path);

    }

    protected void recursiveDeleteExceptRoot(RepositoryPath repositoryPath,
                                             boolean force)
        throws IOException
    {
        RootRepositoryPath root = repositoryPath.getFileSystem().getRootDirectory();
        
        Files.walkFileTree(repositoryPath, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path file,
                                             BasicFileAttributes attrs)
                throws IOException
            {
                // Checksum files will be deleted during directory walking
                doDeletePath((RepositoryPath) file, force, false);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir,
                                                      IOException exc)
                throws IOException
            {
                if (root.equals(dir))
                {
                    return FileVisitResult.CONTINUE;
                }
                
                try
                {
                    Files.delete(unwrap(dir));
                }
                catch (DirectoryNotEmptyException e)
                {
                    String message = Files.list(unwrap(dir))
                                          .map(p -> p.getFileName().toString())
                                          .reduce((p1,
                                                   p2) -> String.format("%s%n%s", p1, p2))
                                          .get();
                    throw new IOException(message, e);
                }
                
                return FileVisitResult.CONTINUE;
            }
        });
    }

    protected void doDeletePath(RepositoryPath repositoryPath,
                                boolean force,
                                boolean deleteChecksum)
        throws IOException
    {
        doDeletePath(repositoryPath, force);

        if (!deleteChecksum)
        {
            return;
        }

        for (RepositoryPath checksumPath : resolveChecksumPathMap(repositoryPath).values())
        {
            if (!Files.exists(unwrap(checksumPath)))
            {
                continue;
            }
            doDeletePath(checksumPath, force);
        }
    }

    public Map<String, RepositoryPath> resolveChecksumPathMap(RepositoryPath repositoryPath)
    {
        Map<String, RepositoryPath> result = new HashMap<>();
        for (String digestAlgorithm : repositoryPath.getFileSystem().getDigestAlgorithmSet())
        {
            // it creates Checksum file extension name form Digest algorithm
            // name: SHA-1->sha1
            String extension = digestAlgorithm.replaceAll("-", "").toLowerCase();
            result.put(digestAlgorithm, repositoryPath.resolveSibling(repositoryPath.getFileName() + "." + extension));
        }
        return result;
    }

    protected void doDeletePath(RepositoryPath repositoryPath,
                                boolean force)
        throws IOException
    {
        Repository repository = repositoryPath.getFileSystem().getRepository();
        if (!repository.isTrashEnabled() || RepositoryFiles.isTrash(repositoryPath))
        {
            Files.deleteIfExists(repositoryPath.getTarget());

            return;
        }

        RepositoryPath trashPath = getTrashPath(repositoryPath);

        Files.move(repositoryPath.getTarget(),
                   trashPath.getTarget(),
                   StandardCopyOption.REPLACE_EXISTING);

        if (force && repository.allowsForceDeletion())
        {
            deleteTrash(repositoryPath);
        }
    }

    public void undelete(RepositoryPath path)
        throws IOException
    {
        Repository repository = path.getFileSystem().getRepository();
        if (!repository.isTrashEnabled())
        {
            return;
        }

        RepositoryPath trashPath = getTrashPath(path);
        if (!Files.exists(trashPath.getTarget()))
        {
            return;
        }

        if (!Files.isDirectory(trashPath.getTarget()))
        {
            Files.move(trashPath.getTarget(), path.getTarget(), StandardCopyOption.REPLACE_EXISTING);
        }
        else
        {
            Files.walkFileTree(trashPath.getTarget(),
                               new MoveDirectoryVisitor(trashPath.getTarget(),
                                       path.getTarget(),
                                       StandardCopyOption.REPLACE_EXISTING));
        }
    }

    public RepositoryPath moveFromTemporaryDirectory(TempRepositoryPath tempPath)
        throws IOException
    {
        logger.debug("Moving [{}]", tempPath.getTarget());
        RepositoryPath path = tempPath.getTempTarget();

        if (!Files.exists(tempPath.getTarget()))
        {
            throw new IOException(String.format("[%s] target for [%s] don't exists!", TempRepositoryPath.class.getSimpleName(), tempPath));
        }

        if (!Files.exists(unwrap(path).getParent()))
        {
            Files.createDirectories(unwrap(path).getParent());
        }
        if (Files.exists(path.getTarget()))
        {
            Files.delete(path.getTarget());
        }
        Files.move(tempPath.getTarget(), path.getTarget(), StandardCopyOption.ATOMIC_MOVE);

        //path.artifactEntry = tempPath.artifactEntry;

        return path;
    }

    public void deleteTrash(RepositoryPath path)
        throws IOException
    {
        Repository repository = path.getFileSystem().getRepository();
        if (!repository.isTrashEnabled())
        {
            return;
        }

        RepositoryPath trashPath = getTrashPath(path);
        if (!Files.exists(trashPath.getTarget()))
        {
            return;
        }
        else
        {
            FileSystemUtils.deleteRecursively(trashPath.getTarget());
            Files.createDirectories(trashPath);
        }
    }

    protected RepositoryPath getTrashPath(RepositoryPath path)
        throws IOException
    {
        if (RepositoryFiles.isTrash(path))
        {
            return path;
        }

        RepositoryPath trashBasePath = path.getFileSystem().getTrashPath();
        RepositoryPath trashPath = rebase(path, trashBasePath);

        if (!Files.exists(trashPath.getParent().getTarget()))
        {
            logger.debug("Creating: [{}]", trashPath.getParent());

            Files.createDirectories(trashPath.getParent().getTarget());
        }

        return trashPath;
    }

    protected static RepositoryPath rebase(RepositoryPath source,
                                           RepositoryPath targetBase)
    {
        String sourceRelative = source.getRoot().relativize(source.getTarget()).toString();

        // XXX[SBESPALOV]: We try to convert path from source to target
        // FileSystem and need to check this on different
        // Storage types.
        // Note that this is only draft implementation, and probably in the
        // future we will need something like separate
        // `FileSystemPathConverter` to convert Paths from one FileSystem to
        // another. Such a `FileSystemPathConverter`
        // can be provided by the `RepositoryFileSystem` instance.

        String sTargetPath = sourceRelative;

        return targetBase.resolve(sTargetPath).toAbsolutePath();
    }

    @Override
    public OutputStream newOutputStream(Path path,
                                        OpenOption... options)
        throws IOException
    {
        TempRepositoryPath temp = RepositoryFiles.temporary((RepositoryPath) path);
        
        return new TempOutputStream(temp, options);
    }

    public void copy(Path source,
                     Path target,
                     CopyOption... options)
        throws IOException
    {
        getTarget().copy(unwrap(source), unwrap(target), options);
    }

    public void move(Path source,
                     Path target,
                     CopyOption... options)
        throws IOException
    {
        getTarget().move(unwrap(source), unwrap(target), options);
    }

    public boolean isSameFile(Path path,
                              Path path2)
        throws IOException
    {
        return getTarget().isSameFile(unwrap(path), unwrap(path2));
    }

    public boolean isHidden(Path path)
        throws IOException
    {
        return getTarget().isHidden(unwrap(path));
    }

    public FileStore getFileStore(Path path)
        throws IOException
    {
        return getTarget().getFileStore(unwrap(path));
    }

    public void checkAccess(Path path,
                            AccessMode... modes)
        throws IOException
    {
        getTarget().checkAccess(unwrap(path), modes);
    }

    public <V extends FileAttributeView> V getFileAttributeView(Path path,
                                                                Class<V> type,
                                                                LinkOption... options)
    {
        return getTarget().getFileAttributeView(unwrap(path), type, options);
    }

    public <A extends BasicFileAttributes> A readAttributes(Path path,
                                                            Class<A> type,
                                                            LinkOption... options)
        throws IOException
    {
        if (RepositoryFileAttributes.class.isAssignableFrom(type) && !RepositoryPath.class.isInstance(path))
        {
            throw new IOException(String.format("Requested path is not [%s].", RepositoryPath.class.getSimpleName()));
        }

        BasicFileAttributes targetAttributes = getTarget().readAttributes(unwrap(path),
                                                                                        BasicFileAttributes.class,
                                                                                        options);
        if (!RepositoryFileAttributes.class.isAssignableFrom(type))
        {
            return (A) targetAttributes;
        }

        RepositoryFileAttributes repositoryFileAttributes = new RepositoryFileAttributes(targetAttributes,
                getRepositoryFileAttributes((RepositoryPath) path,
                                            RepositoryFiles.parseAttributes("*")
                                                           .toArray(new RepositoryFileAttributeType[] {})));

        return (A) repositoryFileAttributes;
    }

    public Map<String, Object> readAttributes(Path path,
                                              String attributes,
                                              LinkOption... options)
        throws IOException
    {
        if (!RepositoryPath.class.isInstance(path))
        {
            return getTarget().readAttributes(path, attributes, options);
        }

        RepositoryPath repositoryPath = (RepositoryPath) path;

        Map<String, Object> result = new HashMap<>();
        if (!attributes.startsWith(STRONGBOX_SCHEME))
        {
            result.putAll(getTarget().readAttributes(unwrap(path), attributes, options));
            if (!attributes.equals("*"))
            {
                return result;
            }
        }

        Set<RepositoryFileAttributeType> targetRepositoryAttributes = new HashSet<>(
                RepositoryFiles.parseAttributes(attributes));

        final Map<RepositoryFileAttributeType, Object> repositoryFileAttributes = new HashMap<>();
        for (Iterator<RepositoryFileAttributeType> iterator = targetRepositoryAttributes.iterator(); iterator.hasNext();)
        {
            RepositoryFileAttributeType repositoryFileAttributeType = iterator.next();
            Optional.ofNullable(repositoryPath.cachedAttributes.get(repositoryFileAttributeType))
                    .ifPresent(v -> {
                        repositoryFileAttributes.put(repositoryFileAttributeType, v);
                        iterator.remove();
                    });

        }
        if (!targetRepositoryAttributes.isEmpty())
        {
            Map<RepositoryFileAttributeType, Object> newAttributes = getRepositoryFileAttributes(repositoryPath,
                                                                                                 targetRepositoryAttributes.toArray(new RepositoryFileAttributeType[targetRepositoryAttributes.size()]));
            newAttributes.entrySet()
                         .stream()
                         .forEach(e -> {
                             repositoryFileAttributes.put(e.getKey(),
                                                          e.getValue());
                             repositoryPath.cachedAttributes.put(e.getKey(),
                                                                 e.getValue());
                         });
        }

        result.putAll(repositoryFileAttributes.entrySet()
                                              .stream()
                                              .collect(Collectors.toMap(e -> e.getKey()
                                                                              .getName(),
                                                                        e -> e.getValue())));

        return result;
    }

    protected abstract Map<RepositoryFileAttributeType, Object> getRepositoryFileAttributes(RepositoryPath repositoryRelativePath,
                                                                                            RepositoryFileAttributeType... attributeTypes)
        throws IOException;

    public void setAttribute(Path path,
                             String attribute,
                             Object value,
                             LinkOption... options)
        throws IOException
    {
        getTarget().setAttribute(unwrap(path), attribute, value, options);
    }

    protected Path unwrap(Path path)
    {
        return path instanceof RepositoryPath ? ((RepositoryPath) path).getTarget() : path;
    }

    private FileSystemProvider getTarget()
    {
        return target;
    }
    
    public static class MoveDirectoryVisitor
            extends SimpleFileVisitor<Path>
    {

        private final Path fromPath;
        private final Path toPath;
        private final CopyOption copyOption;

        public MoveDirectoryVisitor(Path fromPath,
                                    Path toPath,
                                    CopyOption copyOption)
        {
            this.fromPath = fromPath;
            this.toPath = toPath;
            this.copyOption = copyOption;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir,
                                                 BasicFileAttributes attrs)
            throws IOException
        {
            Path targetPath = toPath.resolve(fromPath.relativize(dir));
            if (!Files.exists(targetPath))
            {
                Files.createDirectory(targetPath);
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file,
                                         BasicFileAttributes attrs)
            throws IOException
        {
            Files.move(file, toPath.resolve(fromPath.relativize(file)), copyOption);

            return FileVisitResult.CONTINUE;
        }
    }

    
    private class TempOutputStream extends ProxyOutputStream
    {

        private TempRepositoryPath path;

        public TempOutputStream(TempRepositoryPath path,
                                OpenOption... options)
            throws IOException
        {
            super(StorageFileSystemProvider.super.newOutputStream(unwrap(path), options));

            this.path = path;
        }

        @Override
        public void close()
            throws IOException
        {
            super.close();

            try
            {
                moveFromTemporaryDirectory(path);
            } 
            finally
            {
                if (Files.exists(path))
                {
                    Files.delete(path.getTarget());
                }
            }
        }

    }

    private static class LayoutDirectoryStreamFilter implements Filter<Path>
    {

        private final Filter<? super Path> delegate;
        private final Path root;

        public LayoutDirectoryStreamFilter(Path root, Filter<? super Path> delegate)
        {
            this.delegate = delegate;
            this.root = root;
        }

        @Override
        public boolean accept(Path p)
            throws IOException
        {
            if (p.isAbsolute() && !p.startsWith(root.resolve(LayoutFileSystem.TRASH))
                    && !p.startsWith(root.resolve(LayoutFileSystem.TEMP)))
            {
                return delegate == null ? true : delegate.accept(p);
            }

            return false;
        }

    }

}
