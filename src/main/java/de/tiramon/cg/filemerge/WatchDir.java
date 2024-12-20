package de.tiramon.cg.filemerge;

import de.tiramon.cg.filemerge.java.JavaProject;

import java.io.*;
import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Example to watch a directory (or tree) for changes to files.
 */

public class WatchDir {
	private static CodeProject project = new JavaProject();
	private static Map<Path, File> outputfiles = new HashMap<>();

	private final WatchService watcher;
	private final Map<WatchKey, Path> keys;
	private final boolean recursive;
	private boolean trace = true;
	private final File outputFile;

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> cast(WatchEvent<?> event) {
		return (WatchEvent<T>) event;
	}

	/**
	 * Register the given directory with the WatchService
	 */
	private void register(Path dir) throws IOException {
		WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
		if (trace) {
			Path prev = keys.get(key);
			if (prev == null) {
				System.out.format("register: %s\n", dir);
			} else {
				if (!dir.equals(prev)) {
					System.out.format("update: %s -> %s\n", prev, dir);
				}
			}
		}
		keys.put(key, dir);
	}

	/**
	 * Register the given directory, and all its sub-directories, with the WatchService.
	 */
	private void registerAll(final Path start) throws IOException {
		// register directory and sub-directories
		Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				register(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Creates a WatchService and registers the given directory
	 */
	WatchDir(List<Path> dir, File file) throws IOException {
		watcher = FileSystems.getDefault().newWatchService();
		keys = new HashMap<>();
		outputFile = file;
		recursive = true;

		System.out.format("Scanning %s ...\n", dir);
		for (Path path : dir) {
			registerAll(path);
		}
		System.out.println("Done.");

		// enable trace after initial registration
		trace = true;
	}

	/**
	 * Process all events for keys queued to the watcher
	 *
	 * @throws FileNotFoundException
	 */
	void processEvents() throws FileNotFoundException {
		for (;;) {
			// wait for key to be signalled
			WatchKey key;
			try {
				key = watcher.take();
			} catch (InterruptedException x) {
				return;
			}

			Path dir = keys.get(key);
			if (dir == null) {
				System.err.println("WatchKey not recognized!!");
				continue;
			}

			for (WatchEvent<?> event : key.pollEvents()) {
				WatchEvent.Kind kind = event.kind();

				// TBD - provide example of how OVERFLOW event is handled
				if (kind == OVERFLOW) {
					continue;
				}

				// Context for directory entry event is the file name of entry
				WatchEvent<Path> ev = cast(event);
				Path name = ev.context();
				Path child = dir.resolve(name);

				// print out event
				// System.out.format("%s: %s\n", event.kind().name(), child);

				// if directory is created, and watching recursively, then
				// register it and its sub-directories
				if (recursive && (kind == ENTRY_CREATE)) {
					try {
						if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
							registerAll(child);
						}
					} catch (IOException x) {
						// ignore to keep sample readbale
					}
				}

				handleRelevantFileChange(event.kind(), child);
			}

			// reset key and remove from set if directory no longer accessible
			boolean valid = key.reset();
			if (!valid) {
				keys.remove(key);

				// all directories are inaccessible
				if (keys.isEmpty()) {
					break;
				}
			}
		}
	}

	private void handleRelevantFileChange(Kind<?> kind, Path child) throws FileNotFoundException {
		if (!Files.isDirectory(child) && child.toString().endsWith(project.getFileExtension())) {
			System.out.format("%s: %s\n", kind.name(), child);
			if (kind == ENTRY_DELETE) {
				// FIXME something is not working here
				files.remove(child.toString());
			} else if (kind == ENTRY_CREATE) {
				files.put(child.toString(), readCodeFile(child.toString(), null));
			} else if (kind == ENTRY_MODIFY) {
				CodeFile file = files.get(child.toString());
				readCodeFile(child.toString(), file);
			}
			System.out.println(Arrays.toString(files.keySet().toArray()));
			createOutput();
		}
	}

	static void usage() {
		System.err.println("usage: java WatchDir srcdir outputdir [once]");
		System.exit(-1);
	}

	Map<String, CodeFile> files = new HashMap<>();

	public void gatherFiles() {
		FilenameFilter filter = (dir, name) -> {
			return name.endsWith(project.getFileExtension());
		};
		for (Path key : keys.values()) {
			String[] files = key.toFile().list(filter);
			for (String f : files) {
				String resolved = key.resolve(f).toString();
				this.files.put(resolved, readCodeFile(resolved, null));
			}
		}
	}

	public void print() {

		for (WatchKey key : keys.keySet()) {
			System.out.println(key);
		}
	}

	public static void main(String[] args) throws IOException {
		// parse arguments
		if ((args.length < 2) || (args.length > 3))
			usage();
		int dirArg = 0;
		int targetArg = 1;
		// register directory and process its events
		List<Path> dir = Stream.of(args[dirArg].split("\\|")).map(Paths::get).collect(Collectors.toList());

		WatchDir d = new WatchDir(dir, new File(args[targetArg]));
		d.gatherFiles();
		d.createOutput();
		if (args.length == 3) {
		  if (args[2].equals("once")) {
			return;
		  }
		}
		d.processEvents();
	}

	private void createOutput() throws FileNotFoundException {
		Merger merger = new Merger();
		String content = merger.merge(files.values());
		merger.writeFile(outputFile, content);
	}

	private static List<CodeFile> readCodeFiles(Set<String> files) throws FileNotFoundException, IOException {
		List<CodeFile> list = new ArrayList<>();
		for (String file : files) {
			list.add(readCodeFile(file, null));
		}
		return list;
	}

	private static CodeFile readCodeFile(String path, CodeFile file) {
		if (file == null) {
			file = new CodeFile();
		}
		file.path = path;
		file.imports.clear();
		System.out.println("reading " + path);
		try (BufferedReader br = new BufferedReader(new FileReader(path))) {
			String line;
			StringBuilder builder = new StringBuilder();
			while ((line = br.readLine()) != null) {
				if (line.startsWith("package ")) {
					file.filePackage = line.substring(8, line.length() - 1);
				} else if (project.isImportLine(line)) {
					file.imports.add(line);
				} else {
					if (line.startsWith("public final class")) {
						line = line.replace("public final class", "final class");
						System.err.println(line);
					} else if (line.startsWith("public class")) {
						line = line.replace("public class", "class");
						System.err.println(line);
					} else if (line.startsWith("public abstract class")) {
						line = line.replace("public abstract class", "abstract class");
						System.err.println(line);
					} else if (line.startsWith("public interface")) {
						line = line.replace("public interface", "interface");
						System.err.println(line);
					} else if (line.startsWith("public enum")) {
						line = line.replace("public enum", "enum");
						System.err.println(line);
					} else if (line.startsWith("public record")) {
						line = line.replace("public record", "record");
						System.err.println(line);
					}
					builder.append(line + "\n");
				}
			}
			file.content = builder.toString();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return file;
	}
}
