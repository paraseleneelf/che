/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.selenium.core.client;

import static java.lang.String.format;
import static org.eclipse.che.selenium.core.utils.WaitUtils.sleepQuietly;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.PreDestroy;
import org.apache.commons.io.IOUtils;
import org.eclipse.che.commons.lang.NameGenerator;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is facade and helper for {@link GHRepository}.
 *
 * @author Dmytro Nochevnov
 */
public class TestGitHubRepository {

  private static final int GITHUB_OPERATION_TIMEOUT_SEC = 1;
  private static final int REPO_CREATION_ATTEMPTS = 6;

  private final String repoName = NameGenerator.generate("EclipseCheTestRepo-", 5);
  private static final Logger LOG = LoggerFactory.getLogger(TestGitHubRepository.class);

  private final GHRepository ghRepo;
  private final GitHub gitHub;

  private final String gitHubUsername;
  private final String gitHubPassword;

  private final List<TestGitHubRepository> submodules = new ArrayList<>();

  /**
   * Creates repository with semi-random name on GitHub for certain {@code gitHubUsername}. Waits
   * until repository is really created.
   *
   * @param gitHubUsername default github user name
   * @param gitHubPassword default github user password
   * @throws IOException
   * @throws InterruptedException
   */
  @Inject
  public TestGitHubRepository(
      @Named("github.username") String gitHubUsername,
      @Named("github.password") String gitHubPassword)
      throws IOException, InterruptedException {
    gitHub = GitHub.connectUsingPassword(gitHubUsername, gitHubPassword);
    ghRepo = create();

    this.gitHubUsername = gitHubUsername;
    this.gitHubPassword = gitHubPassword;
  }

  public enum TreeElementMode {
    BLOB("100644"),
    EXECUTABLE_BLOB("100755"),
    SUBDIRECTORY("040000"),
    SUBMODULE("160000"),
    BLOB_SYMLINK("120000");

    private String mode;

    TreeElementMode(String mode) {
      this.mode = mode;
    }

    public String get() {
      return this.mode;
    }
  }

  public enum GitNodeType {
    BLOB("blob"),
    TREE("tree"),
    COMMIT("commit");

    private String nodeType;

    GitNodeType(String nodeType) {
      this.nodeType = nodeType;
    }

    public String get() {
      return this.nodeType;
    }
  }

  public String getName() {
    return repoName;
  }

  /**
   * Creates reference to branch, tag, ... from master branch.
   *
   * @param refName is a name of branch, tag, etc
   * @return reference to the new branch
   * @throws IOException
   */
  public GHRef createBranchFromMaster(String refName) throws IOException {
    GHRef master = ghRepo.getRef("heads/master");
    return ghRepo.createRef("refs/heads/" + refName, master.getObject().getSha());
  }

  /**
   * Copies content of directory {@code pathToRootContentDirectory} to the GitHub repository. It
   * tries to recreate the file ones again in case of FileNotFoundException occurs.
   *
   * @param pathToRootContentDirectory path to the directory with content
   * @throws IOException
   */
  public void addContent(Path pathToRootContentDirectory) throws IOException {
    addContent(pathToRootContentDirectory, null);
  }

  /**
   * Copies content of directory {@code pathToRootContentDirectory} to the specified branch in the
   * GitHub repository. It tries to recreate the file ones again in case of FileNotFoundException
   * occurs.
   *
   * @param pathToRootContentDirectory path to the directory with content
   * @param branch name of the target branch
   * @throws IOException
   */
  public void addContent(Path pathToRootContentDirectory, String branch) throws IOException {
    Files.walk(pathToRootContentDirectory)
        .filter(Files::isRegularFile)
        .forEach(
            pathToFile -> {
              try {
                createFile(pathToRootContentDirectory, pathToFile, branch);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  /**
   * Changes content of the file
   *
   * @param pathToFile path to specified file
   * @param content content to change
   * @throws IOException
   */
  public void changeFileContent(String pathToFile, String content) throws IOException {
    changeFileContent(pathToFile, content, format("Change file %s", pathToFile));
  }

  /**
   * Changes content of the file
   *
   * @param pathToFile path to specified file
   * @param content content to change
   * @param commitMessage message to commit
   * @throws IOException
   */
  public void changeFileContent(String pathToFile, String content, String commitMessage)
      throws IOException {
    ghRepo.getFileContent(String.format("/%s", pathToFile)).update(content, commitMessage);
  }

  public void deleteFile(String pathToFile) throws IOException {
    ghRepo.getFileContent(pathToFile).delete("Delete file " + pathToFile);
  }

  /**
   * Delete folder with content inside the repository on GitHub.
   *
   * @param folder folder to delete
   * @param deleteCommitMessage commit message which is used to delete the message
   * @throws IOException
   */
  public void deleteFolder(Path folder, String deleteCommitMessage) throws IOException {
    for (GHContent ghContent : ghRepo.getDirectoryContent(folder.toString())) {
      ghContent.delete(deleteCommitMessage);
    }
  }

  @PreDestroy
  public void delete() {
    try {
      ghRepo.delete();
    } catch (IOException e) {
      throw new RuntimeException(e.getMessage(), e);
    }

    submodules.forEach(TestGitHubRepository::delete);
    LOG.info("GitHub repo {} has been removed", ghRepo.getHtmlUrl());
  }

  public String getHtmlUrl() {
    return ghRepo.getHtmlUrl().toString();
  }

  public String getSshUrl() {
    return ghRepo.getSshUrl();
  }

  private GHRepository create() throws IOException, InterruptedException {
    GHRepository repo = gitHub.createRepository(repoName).create();
    ensureRepositoryCreated(repo, System.currentTimeMillis());

    LOG.info("GitHub repo {} has been created", repo.getHtmlUrl());
    return repo;
  }

  private void ensureRepositoryCreated(GHRepository repo, long startCreationTimeInMillisec)
      throws IOException {
    Throwable lastIOException = null;
    for (int i = 0; i < REPO_CREATION_ATTEMPTS; i++) {
      try {
        gitHub.getRepository(repo.getFullName());
        return;
      } catch (IOException e) {
        lastIOException = e;
        LOG.info("Waiting for {} to be created", repo.getHtmlUrl());
        sleepQuietly(GITHUB_OPERATION_TIMEOUT_SEC); // sleep one second
      }
    }

    long durationOfRepoCreationInSec =
        (System.currentTimeMillis() - startCreationTimeInMillisec) / 1000;

    throw new IOException(
        format(
            "GitHub repo %s hasn't been created in %s seconds",
            repo.getHtmlUrl(), durationOfRepoCreationInSec),
        lastIOException);
  }

  /**
   * Creates file in GitHub repository in the specified {@code branch}.
   *
   * @param pathToRootContentDirectory path to the root directory of file locally
   * @param pathToFile path to file locally
   * @param branch name of the target branch
   * @throws IOException
   */
  private void createFile(Path pathToRootContentDirectory, Path pathToFile, String branch)
      throws IOException {
    byte[] contentBytes = Files.readAllBytes(pathToFile);
    String relativePath = pathToRootContentDirectory.relativize(pathToFile).toString();
    String commitMessage = String.format("Add file %s", relativePath);

    try {
      ghRepo.createContent(contentBytes, commitMessage, relativePath, branch);
    } catch (GHFileNotFoundException e) {
      // try to create content once again
      LOG.warn(
          "Error of creation of {} occurred. Is trying to create it once again...",
          ghRepo.getHtmlUrl() + "/" + relativePath);
      sleepQuietly(GITHUB_OPERATION_TIMEOUT_SEC);
      ghRepo.createContent(contentBytes, commitMessage, relativePath);
    }
  }

  public String getRepoSha() throws IOException {
    return getDefaultBranch().getObject().getSha();
  }

  public GHRef getDefaultBranch() throws IOException {
    return ghRepo.getRef("heads/" + ghRepo.getDefaultBranch());
  }

  public void addSubmodule(Path pathToRootContentDirectory, String submoduleName)
      throws IOException, URISyntaxException, InterruptedException {

    TestGitHubRepository submodule = new TestGitHubRepository(gitHubUsername, gitHubPassword);
    submodule.addContent(pathToRootContentDirectory);
    createSubmodule(submodule, submoduleName);
    submodules.add(submodule);
  }

  private void createSubmodule(
      TestGitHubRepository pathToRootContentDirectory, String pathForSubmodule)
      throws IOException, URISyntaxException {
    String submoduleSha = createTreeWithSubmodule(pathToRootContentDirectory, pathForSubmodule);

    GHCommit treeCommit =
        ghRepo.createCommit().tree(submoduleSha).message("Create submodule").create();

    getDefaultBranch().updateTo(treeCommit.getSHA1(), true);
    createGitModulesFile(pathToRootContentDirectory, pathForSubmodule);
  }

  private boolean isGitmodulesFileExist() throws IOException {
    return 0
        < ghRepo
            .getDirectoryContent("")
            .stream()
            .filter(item -> item.getName().equals(".gitmodules"))
            .count();
  }

  private String createTreeWithSubmodule(
      TestGitHubRepository targetRepository, String pathForSubmodule) throws IOException {
    return ghRepo
        .createTree()
        .baseTree(this.getRepoSha())
        .entry(
            pathForSubmodule,
            TreeElementMode.SUBMODULE.get(),
            GitNodeType.COMMIT.get(),
            targetRepository.getRepoSha(),
            null)
        .create()
        .getSha();
  }

  private String getSubmoduleConfig(TestGitHubRepository targetRepository, String pathToSubmodule) {
    String repoName = Paths.get(pathToSubmodule).getFileName().toString();
    String repoUrl = targetRepository.getHtmlUrl() + ".git";
    String modulePattern = "[submodule \"%s\"]\n\tpath = %s\n\turl = %s";

    return String.format(modulePattern, repoName, pathToSubmodule, repoUrl);
  }

  private void createGitModulesFile(TestGitHubRepository targetRepository, String pathForSubmodule)
      throws IOException {
    final String gitmodulesFileName = ".gitmodules";
    String fileAddMessage = "Add " + gitmodulesFileName;
    String fileUpdateMessage = "Update " + gitmodulesFileName;
    String submoduleConfig = getSubmoduleConfig(targetRepository, pathForSubmodule);

    if (isGitmodulesFileExist()) {
      GHContent submoduleFileContent = ghRepo.getFileContent(gitmodulesFileName);
      String fileContent = IOUtils.toString(submoduleFileContent.read());
      String newFileContent = fileContent + "\n" + submoduleConfig;

      submoduleFileContent.update(newFileContent, fileUpdateMessage);
      return;
    }

    ghRepo.createContent(submoduleConfig, fileAddMessage, gitmodulesFileName);
  }
}
