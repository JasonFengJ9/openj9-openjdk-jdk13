/*
 * Copyright (c) 1997, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.security.util;

import java.security.cert.CertPath;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

import sun.security.pkcs.*;
import sun.security.timestamp.TimestampToken;
import sun.misc.BASE64Decoder;

import sun.security.jca.Providers;

public class SignatureFileVerifier {

    /* Are we debugging ? */
    private static final Debug debug = Debug.getInstance("jar");

    /* cache of CodeSigner objects */
    private ArrayList<CodeSigner[]> signerCache;

    private static final String ATTR_DIGEST =
        ("-DIGEST-" + ManifestDigester.MF_MAIN_ATTRS).toUpperCase
        (Locale.ENGLISH);

    /** the PKCS7 block for this .DSA/.RSA/.EC file */
    private PKCS7 block;

    // the content of the raw .SF file as an InputStream
    private InputStream sfStream;

    /** the name of the signature block file, uppercased and without
     *  the extension (.DSA/.RSA/.EC)
     */
    private String name;

    /** the ManifestDigester */
    private ManifestDigester md;

    /** The MANIFEST.MF */
    private Manifest man;

    /** cache of created MessageDigest objects */
    private HashMap<String, MessageDigest> createdDigests;

    /* workaround for parsing Netscape jars  */
    private boolean workaround = false;

    /* for generating certpath objects */
    private CertificateFactory certificateFactory = null;

    /**
     * Create the named SignatureFileVerifier.
     *
     * @param name the name of the signature block file (.DSA/.RSA/.EC)
     *
     * @param rawBytes the raw bytes of the signature block file
     */
    public SignatureFileVerifier(ArrayList<CodeSigner[]> signerCache,
                                 Manifest man,
                                 ManifestDigester md,
                                 String name,
                                 byte rawBytes[])
        throws IOException, CertificateException
    {
        // new PKCS7() calls CertificateFactory.getInstance()
        // need to use local providers here, see Providers class
        Object obj = null;
        try {
            obj = Providers.startJarVerification();
            block = new PKCS7(rawBytes);
            byte[] contentData = block.getContentInfo().getData();
            if (contentData != null) {
                sfStream = new ByteArrayInputStream(contentData);
            }
            certificateFactory = CertificateFactory.getInstance("X509");
        } finally {
            Providers.stopJarVerification(obj);
        }
        this.name = name.substring(0, name.lastIndexOf("."))
                                                   .toUpperCase(Locale.ENGLISH);

        this.man = man;
        this.md = md;
        this.signerCache = signerCache;
    }

    /**
     * returns true if we need the .SF file
     */
    public boolean needSignatureFile()
    {
        return sfStream == null;
    }

    public void setSignatureFile(InputStream ins) {
        this.sfStream = ins;
    }

    /**
     * Utility method used by JarVerifier and JarSigner
     * to determine the signature file names and PKCS7 block
     * files names that are supported
     *
     * @param s file name
     * @return true if the input file name is a supported
     *          Signature File or PKCS7 block file name
     */
    public static boolean isBlockOrSF(String s) {
        return s.endsWith(".SF") || isBlock(s);
    }

    /**
     * Utility method used by JarVerifier to determine PKCS7 block
     * files names that are supported
     *
     * @param s file name
     * @return true if the input file name is a PKCS7 block file name
     */
    public static boolean isBlock(String s) {
        return s.endsWith(".DSA") || s.endsWith(".RSA") || s.endsWith(".EC");
    }

    /** get digest from cache */

    private MessageDigest getDigest(String algorithm)
    {
        if (createdDigests == null)
            createdDigests = new HashMap<String, MessageDigest>();

        MessageDigest digest = createdDigests.get(algorithm);

        if (digest == null) {
            try {
                digest = MessageDigest.getInstance(algorithm);
                createdDigests.put(algorithm, digest);
            } catch (NoSuchAlgorithmException nsae) {
                // ignore
            }
        }
        return digest;
    }

    /**
     * process the signature block file. Goes through the .SF file
     * and adds code signers for each section where the .SF section
     * hash was verified against the Manifest section.
     *
     *
     */
    public void process(Map<String, CodeSigner[]> signers,
            List manifestDigests)
        throws IOException, SignatureException, NoSuchAlgorithmException,
            JarException, CertificateException
    {
        // calls Signature.getInstance() and MessageDigest.getInstance()
        // need to use local providers here, see Providers class
        Object obj = null;
        try {
            obj = Providers.startJarVerification();
            processImpl(signers, manifestDigests);
        } finally {
            Providers.stopJarVerification(obj);
        }

    }

    private void processImpl(Map<String, CodeSigner[]> signers,
            List manifestDigests)
        throws IOException, SignatureException, NoSuchAlgorithmException,
            JarException, CertificateException
    {
        SignatureFileManifest sf = new SignatureFileManifest();
        InputStream ins = sfStream;

        byte[] buffer = new byte[4096];
        int sLen = block.getSignerInfos().length;
        boolean mainOK = false;         // main attributes of SF is available...
        boolean manifestSigned = false; // and it matches MANIFEST.MF
        BASE64Decoder decoder = new BASE64Decoder();

        PKCS7.PKCS7Verifier[] pvs = new PKCS7.PKCS7Verifier[sLen];
        for (int i=0; i<sLen; i++) {
            pvs[i] = PKCS7.PKCS7Verifier.from(block, block.getSignerInfos()[i]);
        }

        /*
         * Verify SF in streaming mode. The chunks of the file are fed into
         * the Manifest object sf and all PKCS7Verifiers. As soon as the main
         * attributes is available, we'll check if manifestSigned is true. If
         * yes, there is no need to fill in sf's entries field, since it should
         * be identical to entries in man.
         */
        while (true) {
            int len = ins.read(buffer);
            if (len < 0) {
                if (!manifestSigned) {
                    sf.update(null, 0, 0);
                }
                break;
            } else {
                for (int i=0; i<sLen; i++) {
                    if (pvs[i] != null) pvs[i].update(buffer, 0, len);
                }
                // Continue reading if verifyManifestHash fails (or, the
                // main attributes is not available yet)
                if (!manifestSigned) {
                    sf.update(buffer, 0, len);
                    if (!mainOK) {
                        try {
                            Attributes attr = sf.getMainAttributes();
                            String version = attr.getValue(
                                    Attributes.Name.SIGNATURE_VERSION);

                            if ((version == null) ||
                                    !(version.equalsIgnoreCase("1.0"))) {
                                // XXX: should this be an exception?
                                // for now we just ignore this signature file
                                return;
                            }

                            mainOK = true;
                            manifestSigned = verifyManifestHash(
                                    sf, md, decoder, manifestDigests);
                        } catch (IllegalStateException ise) {
                            // main attributes not available yet
                        }
                    }
                }
            }
        }
        List<SignerInfo> intResult = new ArrayList<>(sLen);
        for (int i = 0; i < sLen; i++) {
            if (pvs[i] != null) {
                SignerInfo signerInfo = pvs[i].verify();
                if (signerInfo != null) {
                    intResult.add(signerInfo);
                }
            }
        }
        if (intResult.isEmpty()) {
            throw new SecurityException("cannot verify signature block file " +
                                        name);
        }

        SignerInfo[] infos =
                intResult.toArray(new SignerInfo[intResult.size()]);

        CodeSigner[] newSigners = getSigners(infos, block);

        // make sure we have something to do all this work for...
        if (newSigners == null)
            return;

        // verify manifest main attributes
        if (!manifestSigned && !verifyManifestMainAttrs(sf, md, decoder)) {
            throw new SecurityException
                ("Invalid signature file digest for Manifest main attributes");
        }

        Iterator<Map.Entry<String,Attributes>> entries;

        if (manifestSigned) {
            if (debug != null) {
                debug.println("full manifest signature match, "
                        + "update signer info from MANIFEST.MF");
            }
            entries = man.getEntries().entrySet().iterator();
        } else {
            if (debug != null) {
                debug.println("full manifest signature unmatch, "
                        + "update signer info from SF file");
            }
            entries = sf.getEntries().entrySet().iterator();
        }

        // go through each section

        while(entries.hasNext()) {

            Map.Entry<String,Attributes> e = entries.next();
            String name = e.getKey();

            if (manifestSigned ||
                    (verifySection(e.getValue(), name, md, decoder))) {

                if (name.startsWith("./"))
                    name = name.substring(2);

                if (name.startsWith("/"))
                    name = name.substring(1);

                updateSigners(newSigners, signers, name);

                if (debug != null) {
                    debug.println("processSignature signed name = "+name);
                }

            } else if (debug != null) {
                debug.println("processSignature unsigned name = "+name);
            }
        }

        // MANIFEST.MF is always regarded as signed
        updateSigners(newSigners, signers, JarFile.MANIFEST_NAME);
    }

    /**
     * See if the whole manifest was signed.
     */
    private boolean verifyManifestHash(Manifest sf,
                                       ManifestDigester md,
                                       BASE64Decoder decoder,
                                       List manifestDigests)
         throws IOException
    {
        Attributes mattr = sf.getMainAttributes();
        boolean manifestSigned = false;

        // go through all the attributes and process *-Digest-Manifest entries
        for (Map.Entry<Object,Object> se : mattr.entrySet()) {

            String key = se.getKey().toString();

            if (key.toUpperCase(Locale.ENGLISH).endsWith("-DIGEST-MANIFEST")) {
                // 16 is length of "-Digest-Manifest"
                String algorithm = key.substring(0, key.length()-16);

                manifestDigests.add(key);
                manifestDigests.add(se.getValue());
                MessageDigest digest = getDigest(algorithm);
                if (digest != null) {
                    byte[] computedHash = md.manifestDigest(digest);
                    byte[] expectedHash =
                        decoder.decodeBuffer((String)se.getValue());

                    if (debug != null) {
                     debug.println("Signature File: Manifest digest " +
                                          digest.getAlgorithm());
                     debug.println( "  sigfile  " + toHex(expectedHash));
                     debug.println( "  computed " + toHex(computedHash));
                     debug.println();
                    }

                    if (MessageDigest.isEqual(computedHash,
                                              expectedHash)) {
                        manifestSigned = true;
                    } else {
                        //XXX: we will continue and verify each section
                    }
                }
            }
        }
        return manifestSigned;
    }

    private boolean verifyManifestMainAttrs(Manifest sf,
                                        ManifestDigester md,
                                        BASE64Decoder decoder)
         throws IOException
    {
        Attributes mattr = sf.getMainAttributes();
        boolean attrsVerified = true;

        // go through all the attributes and process
        // digest entries for the manifest main attributes
        for (Map.Entry<Object,Object> se : mattr.entrySet()) {
            String key = se.getKey().toString();

            if (key.toUpperCase(Locale.ENGLISH).endsWith(ATTR_DIGEST)) {
                String algorithm =
                        key.substring(0, key.length() - ATTR_DIGEST.length());

                MessageDigest digest = getDigest(algorithm);
                if (digest != null) {
                    ManifestDigester.Entry mde =
                        md.get(ManifestDigester.MF_MAIN_ATTRS, false);
                    byte[] computedHash = mde.digest(digest);
                    byte[] expectedHash =
                        decoder.decodeBuffer((String)se.getValue());

                    if (debug != null) {
                     debug.println("Signature File: " +
                                        "Manifest Main Attributes digest " +
                                        digest.getAlgorithm());
                     debug.println( "  sigfile  " + toHex(expectedHash));
                     debug.println( "  computed " + toHex(computedHash));
                     debug.println();
                    }

                    if (MessageDigest.isEqual(computedHash,
                                              expectedHash)) {
                        // good
                    } else {
                        // we will *not* continue and verify each section
                        attrsVerified = false;
                        if (debug != null) {
                            debug.println("Verification of " +
                                        "Manifest main attributes failed");
                            debug.println();
                        }
                        break;
                    }
                }
            }
        }

        // this method returns 'true' if either:
        //      . manifest main attributes were not signed, or
        //      . manifest main attributes were signed and verified
        return attrsVerified;
    }

    /**
     * given the .SF digest header, and the data from the
     * section in the manifest, see if the hashes match.
     * if not, throw a SecurityException.
     *
     * @return true if all the -Digest headers verified
     * @exception SecurityException if the hash was not equal
     */

    private boolean verifySection(Attributes sfAttr,
                                  String name,
                                  ManifestDigester md,
                                  BASE64Decoder decoder)
         throws IOException
    {
        boolean oneDigestVerified = false;
        ManifestDigester.Entry mde = md.get(name,block.isOldStyle());

        if (mde == null) {
            throw new SecurityException(
                  "no manifiest section for signature file entry "+name);
        }

        if (sfAttr != null) {

            //sun.misc.HexDumpEncoder hex = new sun.misc.HexDumpEncoder();
            //hex.encodeBuffer(data, System.out);

            // go through all the attributes and process *-Digest entries
            for (Map.Entry<Object,Object> se : sfAttr.entrySet()) {
                String key = se.getKey().toString();

                if (key.toUpperCase(Locale.ENGLISH).endsWith("-DIGEST")) {
                    // 7 is length of "-Digest"
                    String algorithm = key.substring(0, key.length()-7);

                    MessageDigest digest = getDigest(algorithm);

                    if (digest != null) {
                        boolean ok = false;

                        byte[] expected =
                            decoder.decodeBuffer((String)se.getValue());
                        byte[] computed;
                        if (workaround) {
                            computed = mde.digestWorkaround(digest);
                        } else {
                            computed = mde.digest(digest);
                        }

                        if (debug != null) {
                          debug.println("Signature Block File: " +
                                   name + " digest=" + digest.getAlgorithm());
                          debug.println("  expected " + toHex(expected));
                          debug.println("  computed " + toHex(computed));
                          debug.println();
                        }

                        if (MessageDigest.isEqual(computed, expected)) {
                            oneDigestVerified = true;
                            ok = true;
                        } else {
                            // attempt to fallback to the workaround
                            if (!workaround) {
                               computed = mde.digestWorkaround(digest);
                               if (MessageDigest.isEqual(computed, expected)) {
                                   if (debug != null) {
                                       debug.println("  re-computed " + toHex(computed));
                                       debug.println();
                                   }
                                   workaround = true;
                                   oneDigestVerified = true;
                                   ok = true;
                               }
                            }
                        }
                        if (!ok){
                            throw new SecurityException("invalid " +
                                       digest.getAlgorithm() +
                                       " signature file digest for " + name);
                        }
                    }
                }
            }
        }
        return oneDigestVerified;
    }

    /**
     * Given the PKCS7 block and SignerInfo[], create an array of
     * CodeSigner objects. We do this only *once* for a given
     * signature block file.
     */
    private CodeSigner[] getSigners(SignerInfo infos[], PKCS7 block)
        throws IOException, NoSuchAlgorithmException, SignatureException,
            CertificateException {

        ArrayList<CodeSigner> signers = null;

        for (int i = 0; i < infos.length; i++) {

            SignerInfo info = infos[i];
            ArrayList<X509Certificate> chain = info.getCertificateChain(block);
            CertPath certChain = certificateFactory.generateCertPath(chain);
            if (signers == null) {
                signers = new ArrayList<CodeSigner>();
            }
            // Append the new code signer
            signers.add(new CodeSigner(certChain, getTimestamp(info)));

            if (debug != null) {
                debug.println("Signature Block Certificate: " +
                    chain.get(0));
            }
        }

        if (signers != null) {
            return signers.toArray(new CodeSigner[signers.size()]);
        } else {
            return null;
        }
    }

    /*
     * Examines a signature timestamp token to generate a timestamp object.
     *
     * Examines the signer's unsigned attributes for a
     * <tt>signatureTimestampToken</tt> attribute. If present,
     * then it is parsed to extract the date and time at which the
     * timestamp was generated.
     *
     * @param info A signer information element of a PKCS 7 block.
     *
     * @return A timestamp token or null if none is present.
     * @throws IOException if an error is encountered while parsing the
     *         PKCS7 data.
     * @throws NoSuchAlgorithmException if an error is encountered while
     *         verifying the PKCS7 object.
     * @throws SignatureException if an error is encountered while
     *         verifying the PKCS7 object.
     * @throws CertificateException if an error is encountered while generating
     *         the TSA's certpath.
     */
    private Timestamp getTimestamp(SignerInfo info)
        throws IOException, NoSuchAlgorithmException, SignatureException,
            CertificateException {

        Timestamp timestamp = null;

        // Extract the signer's unsigned attributes
        PKCS9Attributes unsignedAttrs = info.getUnauthenticatedAttributes();
        if (unsignedAttrs != null) {
            PKCS9Attribute timestampTokenAttr =
                unsignedAttrs.getAttribute("signatureTimestampToken");
            if (timestampTokenAttr != null) {
                PKCS7 timestampToken =
                    new PKCS7((byte[])timestampTokenAttr.getValue());
                // Extract the content (an encoded timestamp token info)
                byte[] encodedTimestampTokenInfo =
                    timestampToken.getContentInfo().getData();
                // Extract the signer (the Timestamping Authority)
                // while verifying the content
                SignerInfo[] tsa =
                    timestampToken.verify(encodedTimestampTokenInfo);
                // Expect only one signer
                ArrayList<X509Certificate> chain =
                                tsa[0].getCertificateChain(timestampToken);
                CertPath tsaChain = certificateFactory.generateCertPath(chain);
                // Create a timestamp token info object
                TimestampToken timestampTokenInfo =
                    new TimestampToken(encodedTimestampTokenInfo);
                // Create a timestamp object
                timestamp =
                    new Timestamp(timestampTokenInfo.getDate(), tsaChain);
            }
        }
        return timestamp;
    }

    // for the toHex function
    private static final char[] hexc =
            {'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};
    /**
     * convert a byte array to a hex string for debugging purposes
     * @param data the binary data to be converted to a hex string
     * @return an ASCII hex string
     */

    static String toHex(byte[] data) {

        StringBuffer sb = new StringBuffer(data.length*2);

        for (int i=0; i<data.length; i++) {
            sb.append(hexc[(data[i] >>4) & 0x0f]);
            sb.append(hexc[data[i] & 0x0f]);
        }
        return sb.toString();
    }

    // returns true if set contains signer
    static boolean contains(CodeSigner[] set, CodeSigner signer)
    {
        for (int i = 0; i < set.length; i++) {
            if (set[i].equals(signer))
                return true;
        }
        return false;
    }

    // returns true if subset is a subset of set
    static boolean isSubSet(CodeSigner[] subset, CodeSigner[] set)
    {
        // check for the same object
        if (set == subset)
            return true;

        for (int i = 0; i < subset.length; i++) {
            if (!contains(set, subset[i]))
                return false;
        }
        return true;
    }

    /**
     * returns true if signer contains exactly the same code signers as
     * oldSigner and newSigner, false otherwise. oldSigner
     * is allowed to be null.
     */
    static boolean matches(CodeSigner[] signers, CodeSigner[] oldSigners,
        CodeSigner[] newSigners) {

        // special case
        if ((oldSigners == null) && (signers == newSigners))
            return true;

        // make sure all oldSigners are in signers
        if ((oldSigners != null) && !isSubSet(oldSigners, signers))
            return false;

        // make sure all newSigners are in signers
        if (!isSubSet(newSigners, signers)) {
            return false;
        }

        // now make sure all the code signers in signers are
        // also in oldSigners or newSigners

        for (int i = 0; i < signers.length; i++) {
            boolean found =
                ((oldSigners != null) && contains(oldSigners, signers[i])) ||
                contains(newSigners, signers[i]);
            if (!found)
                return false;
        }
        return true;
    }

    void updateSigners(CodeSigner[] newSigners,
        Map<String, CodeSigner[]> signers, String name) {

        CodeSigner[] oldSigners = signers.get(name);

        // search through the cache for a match, go in reverse order
        // as we are more likely to find a match with the last one
        // added to the cache

        CodeSigner[] cachedSigners;
        for (int i = signerCache.size() - 1; i != -1; i--) {
            cachedSigners = signerCache.get(i);
            if (matches(cachedSigners, oldSigners, newSigners)) {
                signers.put(name, cachedSigners);
                return;
            }
        }

        if (oldSigners == null) {
            cachedSigners = newSigners;
        } else {
            cachedSigners =
                new CodeSigner[oldSigners.length + newSigners.length];
            System.arraycopy(oldSigners, 0, cachedSigners, 0,
                oldSigners.length);
            System.arraycopy(newSigners, 0, cachedSigners, oldSigners.length,
                newSigners.length);
        }
        signerCache.add(cachedSigners);
        signers.put(name, cachedSigners);
    }
}
