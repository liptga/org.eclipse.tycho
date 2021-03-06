/*******************************************************************************
 * Copyright (c) 2011, 2012 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     SAP AG - initial API and implementation
 *******************************************************************************/

package org.eclipse.tycho.core.ee;

import org.osgi.framework.Version;

public class EEVersion implements Comparable<EEVersion> {

    public enum EEType {

        // order is significant for comparison
        OSGI_MINIMUM("OSGi/Minimum"), CDC_FOUNDATION("CDC/Foundation"), JRE("JRE"), JAVA_SE("JavaSE");

        private final String profileName;

        private EEType(String profileName) {
            this.profileName = profileName;
        }

        public static EEType fromName(String profileName) {
            for (EEType type : values()) {
                if (type.profileName.equals(profileName)) {
                    return type;
                }
            }
            throw new IllegalArgumentException(profileName);
        }
    }

    private Version version;
    private EEType type;

    public EEVersion(Version version, EEType type) {
        this.version = version;
        this.type = type;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    public int compareTo(EEVersion other) {
        int result = type.compareTo(other.type);
        if (result != 0) {
            return result;
        }
        return version.compareTo(other.version);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (!(other instanceof EEVersion)) {
            return false;
        }
        EEVersion o = (EEVersion) other;
        return this.version.equals(o.version) && this.type.equals(o.type);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.type == null) ? 0 : this.type.hashCode());
        result = prime * result + ((this.version == null) ? 0 : this.version.hashCode());
        return result;
    }

}
