package com.example.my_plugin;

import java.util.List;

public class LicenseSurvey {
    public Project project;
    public Author author;
    public Intent intent;
    public Constraints constraints;
    public Compatibility compatibility;
    public SuggestedLicense suggestedLicense;
    public List<String> existingLicensesUsed;
    public List<String> preferredLicenses;
    public String notes;

    public static class Project {
        public String name;
        public String description;
        public String repository;

        public Project() {}
        // Constructor for initializing project details
        public Project(String name, String description, String repository) {
            // What's the name of your project?
            this.name = name;
            // Briefly describe what your project does.
            this.description = description;
            // If your project is hosted on a version control system, please provide the repository URL.
            this.repository = repository;
        }
    }

    public static class Author {
        public String name;
        public String email;
        public String organization;

        public Author() {}
        // Constructor for initializing author details
        public Author(String name, String email, String organization) {
            // What's your name or your organization's name?
            this.name = name;
            // What's your email address?
            this.email = email;
            // If you are representing an organization, please provide its name.
            this.organization = organization;
        }
    }

    public static class Intent {
        public boolean commercialUse;
        public boolean distribution;
        public boolean modificationAllowed;
        public boolean patentGrantRequired;
        public boolean useWithClosedSource;

        public Intent() {}
        // Constructor for initializing intent details
        public Intent(boolean commercialUse, boolean distribution, boolean modificationAllowed, boolean patentGrantRequired, boolean useWithClosedSource) {
            // Will the software be used commercially? (yes/no)
            this.commercialUse = commercialUse;
            // Should others be allowed to distribute your software? (yes/no)
            this.distribution = distribution;
            // Should others be allowed to modify the software? (yes/no)
            this.modificationAllowed = modificationAllowed;
            // Do you want to include a patent grant? (yes/no)
            this.patentGrantRequired = patentGrantRequired;
            // Can your software be used in closed-source products? (yes/no)
            this.useWithClosedSource = useWithClosedSource;
        }
    }

    public static class Constraints {
        public boolean copyleftRequired;
        public boolean mustDiscloseSource;
        public boolean mustDocumentChanges;
        public boolean includeLicenseInBinary;

        public Constraints() {}
        // Constructor for initializing constraints details
        public Constraints(boolean copyleftRequired, boolean mustDiscloseSource, boolean mustDocumentChanges, boolean includeLicenseInBinary) {
            // Do you require that derivative works also be open-source? (yes/no)";
            this.copyleftRequired = copyleftRequired;
            // Must users disclose their source code if they use your code? (yes/no)
            this.mustDiscloseSource = mustDiscloseSource;
            // Do users need to document their changes? (yes/no)
            this.mustDocumentChanges = mustDocumentChanges;
            // Should the license be included in binary distributions? (yes/no)
            this.includeLicenseInBinary = includeLicenseInBinary;
        }
    }

    public static class Compatibility {
        public boolean compatibleWithGPL;
        public boolean compatibleWithApache;
        public boolean compatibleWithMIT;

        public Compatibility() {}
        // Constructor for initializing compatibility details
        public Compatibility(boolean compatibleWithGPL, boolean compatibleWithApache, boolean compatibleWithMIT) {
            // Should your license be compatible with GPL? (yes/no)
            this.compatibleWithGPL = compatibleWithGPL;
            // Should your license be compatible with Apache 2.0? (yes/no)
            this.compatibleWithApache = compatibleWithApache;
            // Should your license be compatible with MIT? (yes/no)
            this.compatibleWithMIT = compatibleWithMIT;
        }
    }

    public static class SuggestedLicense {
        public String name;
        public String url;

        public SuggestedLicense() {}
        // Constructor for initializing suggested license details

        public SuggestedLicense(String name, String url) {
            // What is the suggested license?
            this.name = name;
            // URL to the suggested license text
            this.url = url;
        }

    }
}