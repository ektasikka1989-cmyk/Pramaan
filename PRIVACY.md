# Pramaan — Privacy and data handling

**Last updated: 4 November 2026**

Pramaan is an **experimental hackathon prototype** for trained community health workers. It is **not approved for storing real patient records**. Use the demonstration only with fictional or synthetic people and information. This statement describes the intended design and known risks; it is **not** a certification that every control has passed an independent security audit.

## 1. Information the app may handle

| Category | Examples | Purpose |
|---|---|---|
| Household/person records | Name, village, household, age, telephone, relationship, optional identifiers | Organise visits and follow-ups |
| Maternal and child health records | LMP/EDD, ANC/HBNC/HBYC observations, immunisation, MCP entries | Populate worker-reviewed records |
| Visit notes and images | Spoken notes, transcripts, photographed slips, MCP cards and registers | Extract suggested fields and find relevant guidance |
| Referrals and stock | Referral notes, pending visits, medicine quantities, requests, reports | Coordination and accountability |
| Settings and access | Local/Cloud choice, model setup information, optional Meta API key | Configure processing |

The application is designed to store working records on the Android device. The source describes encryption using AES-GCM and Android Keystore for protected fields, but comprehensive verification of encryption coverage, backups and device logs is still required. Do not assume that every temporary file, cache or exported document is encrypted.

## 2. Offline versus cloud processing

**Local processing:** Once the needed models and guides are installed, on-device Llama 3.2, Whisper, OCR and retrieval components can process supported tasks without cloud inference. Local results may still be inaccurate, and a device can be lost or compromised.

**Optional cloud processing:** With Cloud first, an explicit online action or an enabled fallback pathway, the app may send information to the **Meta Model API**. Depending on the feature, this can include:

| Feature | Potentially transmitted material | Important limitation |
|---|---|---|
| Ask SOP / Check online | User's question and retrieved guide passages | Do not include names or patient identifiers in the question |
| AI form drafting/explanation | Selected visit text or extracted fields | Identifier scrubbing may miss indirect or unusual identifiers |
| Muse Voice Transcribe | Voice recording | Spoken names or health details cannot reliably be removed before transcription |
| Muse Spark vision | Selected photograph | OCR-based Aadhaar masking can fail to find blurred, handwritten or unusual identifiers; other personal information may remain |
| SAM 3.1 stock count | Drug-kit photograph | Background labels, faces or documents could still be visible |

The app contains code paths intended to remove selected identifiers from text and redact detected Aadhaar-number areas from images. These safeguards **do not guarantee de-identification**. In particular, a photograph may include names, faces, addresses and medical details beyond Aadhaar. **Never test cloud vision or voice with real patient material in the prototype.**

Meta's processing, retention, geographic processing location, model-training policies, pricing and access controls are governed by the provider's applicable terms and your account configuration. Pramaan cannot promise that cloud-bound data remains on the phone or is deleted by Meta when the local record is deleted. Review Meta's current terms before enabling cloud features.

## 3. Sharing with ANM or PHC

Pramaan can help prepare a referral or visit summary for an Auxiliary Nurse Midwife (ANM), Primary Health Centre (PHC), or other authorised care team member. Where supported, a worker may deliberately share text through a device application such as WhatsApp.

**A prepared message is not a confirmed delivery.** Sharing is user-controlled and subject to the recipient application's own data-handling policies. The prototype does **not** claim an authorised connection, automatic sync or official submission to government systems such as RCH.

## 4. Backups, exports and deletion

The supplied design describes an optional password-protected backup that the user saves through Android's file picker. A chosen provider, such as Google Drive, has its own policies. Backup encryption, restore and password-loss behaviour should be independently tested before use. Exported PDFs, screenshots, share sheets, saved files and messages can remain accessible outside the app.

Android's Clear storage and uninstall actions generally remove app-managed local data, subject to OS backups and external copies. They do **not** revoke information already sent to a cloud service, recipient, external document or user-selected backup. Delete such copies separately through the relevant service.

## 5. Logging and access

Development builds may generate Android diagnostic logs, exception traces and temporary files. Do not assume that logs can never contain user input or health information until this has been tested. The public repository must contain no real patient data, production API keys, credentials or identifiable screenshots.

The application is not presented as having a government identity-management system or audited role-based clinical access. Anyone testing the prototype should use a dedicated test device or synthetic records and avoid adding identifiable health data.

## 6. Clinical and privacy limits

- Sources and page citations help a trained worker verify guidance but do not guarantee current clinical correctness.
- Fixed rules can miss danger signs when speech/OCR/input is incomplete. Professional judgment and existing referral protocols remain essential.
- A future production deployment would require clinical review, updated official content, informed notice/consent, purpose and retention rules, access controls, independent security testing, cloud-provider assessment, grievance handling and applicable Indian data-protection compliance.
- A future ANM adaptation would require its own validation and data-access model; changing the knowledge base alone is insufficient.

## 7. Project contact

For prototype issues or privacy concerns, use the repository's [GitHub Issues](https://github.com/ektasikka1989-cmyk/Pramaan/issues) without posting personal health information, credentials or identifiable records.

This document should be updated after actual device, cloud-payload and backup verification. It does not replace a legally reviewed production privacy notice.
