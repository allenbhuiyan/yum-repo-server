package de.is24.infrastructure.gridfs.http.security;

public class Permission {
  public static final String READ_FILE = "READ_FILE";
  public static final String PROPAGATE_FILE = "PROPAGATE_FILE";
  public static final String PROPAGATE_REPO = "PROPAGATE_REPO";

  public static final String HAS_DESCRIPTOR_READ_PERMISSION = "hasPermission(#descriptor, '" + READ_FILE + "')";
}
