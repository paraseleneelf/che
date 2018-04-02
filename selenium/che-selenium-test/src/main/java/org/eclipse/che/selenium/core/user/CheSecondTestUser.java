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
package org.eclipse.che.selenium.core.user;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/**
 * Second {@link TestUser} that will be created before all tests and will be deleted after them.
 *
 * @author Dmytro Nochevnov
 */
@Singleton
public class CheSecondTestUser implements TestUser {

  private final TestUser delegate;

  @Inject
  public CheSecondTestUser(
      TestUserFactory userFactory,
      @Named("che.second.testuser.name") String name,
      @Named("che.second.testuser.email") String email,
      @Named("che.second.testuser.password") String password,
      @Named("che.second.testuser.offline_token") String offlineToken)
      throws Exception {
    this.delegate = userFactory.create(name, email, password, offlineToken);
  }

  @Override
  public String getEmail() {
    return delegate.getEmail();
  }

  @Override
  public String getPassword() {
    return delegate.getPassword();
  }

  @Override
  public String obtainAuthToken() {
    return delegate.obtainAuthToken();
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public String getOfflineToken() {
    return delegate.getOfflineToken();
  }

  @Override
  public void cleanUp() {}
}
