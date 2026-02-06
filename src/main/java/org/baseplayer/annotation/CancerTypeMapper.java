package org.baseplayer.annotation;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps various cancer type names and abbreviations to standardized display names.
 * Handles variations like "CRC", "colorectal", "colon cancer" -> "colorectal cancer"
 */
public final class CancerTypeMapper {
  
  private static final Map<String, String> CANCER_TYPE_MAP = new HashMap<>();
  
  static {
    // Initialize the mapping with comprehensive cancer type standardizations
    
    // Colorectal / Colon
    mapVariations("colorectal cancer",
        "CRC", "crc", "colon", "colon cancer", "colon carcinoma", "colon adenocarcinoma",
        "colorectal", "colorectal adenocarcinoma", "colorectal carcinoma", 
        "colorectal cancer susceptibility", "large intestine", "large intestine carcinoma");
    
    // Lung cancers
    mapVariations("non-small cell lung cancer",
        "NSCLC", "nsclc", "lung adenocarcinoma", "lung adenocarcinoma (mucinous)");
    mapVariations("small cell lung cancer",
        "SCLC", "sclc", "small cell lung carcinoma");
    mapVariations("lung cancer",
        "lung", "lung carcinoma", "lung SCC", "Lung SCC");
    
    // Breast cancer
    mapVariations("breast cancer",
        "breast", "breast carcinoma", "lobular breast", "luminal A breast",
        "secretory breast", "triple-negative breast cancer", "fibroadenoma");
    
    // Leukaemias
    mapVariations("acute myeloid leukaemia",
        "AML", "aml", "AML*", "de novo AML", "paediatric AML", "sAML",
        "acute megakaryocytic leukaemia", "promyelocytic leukaemia",
        "megakaryoblastic leukaemia of Downs syndrome");
    mapVariations("acute lymphoblastic leukaemia",
        "ALL", "all", "pre B-ALL", "pre-B ALL", "B-ALL", "T-ALL", "ETP ALL",
        "Downs associated ALL", "Down syndrome associated ALL", "relapse ALL",
        "lymphoblastic leukaemia/biphasic");
    mapVariations("acute promyelocytic leukaemia",
        "APL", "apl", "APL (translocation)");
    mapVariations("chronic myeloid leukaemia",
        "CML", "cml", "aCML", "AML (CML blast transformation)");
    mapVariations("chronic lymphocytic leukaemia",
        "CLL", "cll", "B-CLL", "T-CLL", "chemorefractory CLL",
        "large granular lymphocytic leukaemia", "T-cell large granular lymphocytic leukaemia",
        "paediatric large granular lymphocytic leukaemia", "monoclonal B lymphocytosis");
    mapVariations("chronic myelomonocytic leukaemia",
        "CMML", "cmml");
    mapVariations("acute eosinophilic leukaemia",
        "AEL", "ael");
    mapVariations("juvenile myelomonocytic leukaemia",
        "JMML", "jmml");
    mapVariations("T-cell prolymphocytic leukaemia",
        "T-PLL", "t-pll", "T cell prolymphocytic leukaemia");
    mapVariations("adult T-cell leukaemia/lymphoma",
        "adult T-cell lymphoma-leukaemia");
    mapVariations("leukaemia",
        "L", "various leukaemia", "T-cell leukaemia",
        "leukaemia  (FANCB", "FANCD1)",
        "leukaemia lymphoma and MDS associated with severe congenital neutropenia");
    
    // Lymphomas
    mapVariations("diffuse large B-cell lymphoma",
        "DLBCL", "dlbcl", "ABC-DLBCL");
    mapVariations("Burkitt lymphoma",
        "paediatric Burkitt lymphoma");
    mapVariations("follicular lymphoma");
    mapVariations("Hodgkin lymphoma",
        "Nodular lymphocyte predominant Hodgkin lymphoma");
    mapVariations("mantle cell lymphoma",
        "MCL", "mcl");
    mapVariations("marginal zone lymphoma",
        "marginal zone B-cell lymphomas", "SMZL", "smzl",
        "splenic marginal zone lymphoma");
    mapVariations("MALT lymphoma",
        "MALT", "malt");
    mapVariations("primary mediastinal B-cell lymphoma",
        "PMBL", "pmbl");
    mapVariations("non-Hodgkin lymphoma",
        "NHL", "nhl", "B-NHL", "BNHL");
    mapVariations("anaplastic large cell lymphoma",
        "ALCL", "alcl");
    mapVariations("peripheral T-cell lymphoma",
        "T-cell lymphoma", "T cell lymphomas");
    mapVariations("primary CNS lymphoma",
        "primary central nervous system lymphoma");
    mapVariations("nasal NK/T lymphoma");
    mapVariations("intestinal T-cell lymphoma");
    mapVariations("Langerhans cell histiocytosis");
    mapVariations("lymphoma",
        "lymphomas", "lethal midline carcinoma", "lethal midline carcinoma of young people",
        "MLCLS", "multiple different leukaemia and lymphoma tumour types including ALL");
    
    // Myelodysplastic / Myeloproliferative
    mapVariations("myelodysplastic syndrome",
        "MDS", "mds", "MDS and related", "MDS/MPN-U", "myelodysplastic syndrome");
    mapVariations("myeloproliferative neoplasm",
        "MPN", "mpn", "myeloid neoplasms");
    mapVariations("chronic neutrophilic leukaemia",
        "CNL", "cnl");
    mapVariations("mastocytosis");
    mapVariations("hypereosinophilic syndrome",
        "HES", "hes", "idiopathic hypereosinophilic syndrome", "SM-AHD");
    
    // Multiple myeloma
    mapVariations("multiple myeloma",
        "MM", "mm", "myeloma");
    mapVariations("Waldenstrom macroglobulinemia",
        "WM", "wm");
    
    // Renal cancers
    mapVariations("clear cell renal cell carcinoma",
        "CCRCC", "ccrcc", "clear cell renal carcinoma");
    mapVariations("renal cell carcinoma",
        "RCC", "rcc", "papillary renal", "renal cell carcinoma (childhood epithelioid)");
    mapVariations("kidney cancer",
        "renal");
    mapVariations("renal angiomyolipoma");
    
    // Ovarian cancers
    mapVariations("ovarian cancer",
        "ovarian", "ovarian carcinoma", "ovary", "epithelial ovarian",
        "epithelial ovarian cancer", "borderline ovarian", "serous ovarian",
        "low grade serous ovarian cancer", "endometrioid cancer", "endometrioid carcinoma",
        "clear cell ovarian carcinoma", "tubo-ovarian carcinoma");
    mapVariations("ovarian germ cell tumour",
        "ovarian mixed germ cell tumour");
    mapVariations("granulosa cell tumour",
        "granulosa-cell tumour of the ovary", "sex cord-stromal tumour");
    
    // Endometrial cancers
    mapVariations("endometrial cancer",
        "endometrial", "endometrial carcinoma", "endometrium",
        "endometrioid adenocarcinoma", "uterine serous carcinoma");
    mapVariations("endometrial stromal tumour",
        "endometrial stromal sarcoma");
    mapVariations("uterine carcinosarcoma");
    mapVariations("uterine leiomyoma",
        "leiomyoma", "cutaneous leiomyoma");
    
    // Gastric cancers
    mapVariations("gastric cancer",
        "gastric", "gastric adenocarcinoma", "gastric carcinoma",
        "diffuse gastric", "stomach carcinoma");
    
    // Oesophageal cancers
    mapVariations("oesophageal cancer",
        "oesophagus", "oesophagus cancer", "oesophageal SCC",
        "oesophageal squamous cell carcinoma");
    
    // Pancreatic cancers
    mapVariations("pancreatic cancer",
        "pancreas", "pancreatic", "pancreatic carcinoma",
        "pancreatic ductal adenocarcinoma", "pancreas acinar carcinoma",
        "pancreatic acinar cell carcinoma");
    mapVariations("pancreatic neuroendocrine tumour",
        "pancreatic neuroendocrine tumours", "pancreatic islet cell",
        "pancreatic intraductal papillary mucinous neoplasm");
    
    // Liver cancers
    mapVariations("hepatocellular carcinoma",
        "hepatocellular", "liver", "fibrolamellar hepatocellular carcinoma");
    mapVariations("hepatoblastoma");
    mapVariations("hepatic adenoma");
    mapVariations("cholangiocarcinoma");
    
    // Biliary tract
    mapVariations("biliary tract cancer",
        "biliary tract", "biliary tract carcinoma");
    mapVariations("gallbladder carcinoma");
    
    // Prostate cancer
    mapVariations("prostate cancer",
        "prostate", "prostate carcinoma", "prostae adenocarcinoma");
    
    // Bladder cancer
    mapVariations("bladder cancer",
        "bladder", "bladder carcinoma", "urothelial cancer",
        "urothelial cell carcinoma");
    
    // Testicular cancer
    mapVariations("testicular germ cell tumour",
        "testicular", "TGCT", "tgct",
        "testicular germ cell tumours and other tumour types");
    
    // Cervical cancer
    mapVariations("cervical carcinoma",
        "cervical SCC");
    
    // Thyroid cancers
    mapVariations("thyroid cancer",
        "thyroid", "thyroid gland follicular carcinoma",
        "thyroid cancer (PDTC and ATC)");
    mapVariations("papillary thyroid cancer",
        "papillary thyroid");
    mapVariations("follicular thyroid cancer",
        "follicular thyroid", "follicular thyroid adenoma", "microfollicular thyroid adenoma");
    mapVariations("medullary thyroid cancer",
        "medullary thyroid");
    mapVariations("anaplastic thyroid cancer",
        "anaplastic thyroid");
    mapVariations("toxic thyroid adenoma");
    
    // Head and neck cancers
    mapVariations("head and neck cancer",
        "head and neck", "head and neck SCC", "HNSCC", "hnscc",
        "head-neck squamous cell");
    mapVariations("oral squamous cell carcinoma",
        "oral SCC", "oral squamous cell");
    mapVariations("oropharyngeal cancer",
        "oropharyngeal");
    
    // Skin cancers
    mapVariations("melanoma",
        "malignant melanoma", "cutaneous melanoma", "skin and uveal melanoma",
        "desmoplastic melanoma", "mucosal melanoma", "malignant melanoma of soft parts");
    mapVariations("uveal melanoma");
    mapVariations("basal cell carcinoma",
        "skin basal cell", "skin basal cell carcinoma");
    mapVariations("squamous cell carcinoma",
        "SCC", "scc", "skin SCC", "skin sqamous cell", "skin squamous cell",
        "skin squamous cell carcinoma-burn scar related");
    mapVariations("skin cancer",
        "skin");
    mapVariations("eyelid sebaceous carcinoma");
    mapVariations("melanocytic nevus");
    mapVariations("Spitzoid tumour");
    
    // Brain tumours
    mapVariations("glioblastoma",
        "GBM", "gbm", "paediatric GBM", "paediatric glioblastoma");
    mapVariations("glioma");
    mapVariations("medulloblastoma",
        "sporadic medulloblastoma");
    mapVariations("oligodendroglioma");
    mapVariations("pilocytic astrocytoma");
    mapVariations("diffuse intrinsic pontine glioma",
        "DIPG", "dipg");
    mapVariations("ependymoma",
        "supratentorial ependymoma");
    mapVariations("ganglioglioma");
    mapVariations("angiocentric glioma");
    mapVariations("CNS tumour",
        "CNS", "cns", "CNS tumours", "central nervous system tumours",
        "other CNS");
    mapVariations("meningioma",
        "clear cell meningioma", "meningeal haemangiopericytoma");
    mapVariations("hemangioblastoma",
        "central nervous system hemangioblastomas");
    mapVariations("schwannoma",
        "acoustic neuroma");
    mapVariations("intracranial germ cell tumour");
    mapVariations("neuroepithelial tumour",
        "neuroepithelial tumours");
    mapVariations("primary CNS melanocytic neoplasm",
        "primary central nervous system melanocytic neoplasms");
    
    // Sarcomas
    mapVariations("osteosarcoma");
    mapVariations("Ewing sarcoma",
        "Ewing's sarcoma");
    mapVariations("rhabdomyosarcoma",
        "alveolar rhabdomyosarcoma", "embryonal rhabdomyosarcoma");
    mapVariations("synovial sarcoma");
    mapVariations("liposarcoma");
    mapVariations("chondrosarcoma",
        "myxoid chondrosarcoma", "extraskeletal myxoid chondrosarcoma",
        "mesenchymal chondrosarcoma");
    mapVariations("clear cell sarcoma",
        "clear cell sarcoma of soft parts");
    mapVariations("myxofibrosarcoma");
    mapVariations("fibromyxoid sarcoma");
    mapVariations("angiosarcoma");
    mapVariations("leiomyosarcoma");
    mapVariations("malignant peripheral nerve sheath tumour",
        "malignant peripheral nerve sheath tumours");
    mapVariations("dermatofibrosarcoma protuberans",
        "DFSP", "dfsp");
    mapVariations("desmoplastic small round cell tumour");
    mapVariations("alveolar soft part sarcoma");
    mapVariations("epithelioid haemangioendothelioma");
    mapVariations("inflammatory myofibroblastic tumour");
    mapVariations("solitary fibrous tumour");
    mapVariations("soft tissue sarcoma",
        "soft-tissue sarcoma", "sarcoma", "infrequent sarcomas");
    
    // Bone tumours
    mapVariations("chondroblastoma");
    mapVariations("enchondroma");
    mapVariations("aneurysmal bone cyst");
    mapVariations("fibrous dysplasia");
    mapVariations("multiple ossifying jaw fibroma");
    mapVariations("exostoses");
    
    // Gastrointestinal stromal tumour
    mapVariations("gastrointestinal stromal tumour",
        "GIST", "gist");
    
    // Neuroblastoma
    mapVariations("neuroblastoma",
        "ganglioneuroblastoma");
    
    // Wilms tumour
    mapVariations("Wilms tumour");
    
    // Retinoblastoma
    mapVariations("retinoblastoma");
    
    // Mesothelioma
    mapVariations("malignant mesothelioma",
        "mesothelioma");
    
    // Thymoma
    mapVariations("thymoma");
    
    // Pheochromocytoma / Paraganglioma
    mapVariations("pheochromocytoma and paraganglioma",
        "pheochromocytoma", "paraganglioma");
    
    // Adrenal tumours
    mapVariations("adrenocortical carcinoma",
        "adrenocortical");
    mapVariations("adrenal adenoma",
        "adrenal aldosterone producing adenoma", "cortisol secreting adrenal adenoma");
    
    // Pituitary tumours
    mapVariations("pituitary adenoma",
        "pituitary", "corticotroph adenoma");
    mapVariations("pituitary blastoma");
    
    // Parathyroid tumours
    mapVariations("parathyroid tumour",
        "parathyroid", "parathyroid adenoma", "parathyroid tumours");
    
    // Salivary gland tumours
    mapVariations("salivary gland mucoepidermoid carcinoma",
        "salivary gland mucoepidermoid");
    mapVariations("adenoid cystic carcinoma");
    mapVariations("pleomorphic salivary gland adenoma",
        "salivary adenoma");
    mapVariations("polymorphous adenocarcinoma",
        "polymorphous adenocarcinoma of the minor salivary glands");
    mapVariations("cribriform adenocarcinoma",
        "cribriform adenocarcinoma of the minor salivary glands");
    mapVariations("myoepithelioma");
    
    // Small intestine tumours
    mapVariations("small intestine neuroendocrine tumour",
        "small intestine", "small intestine neuroendocrine tumours",
        "carcinoid");
    
    // Endocrine tumours
    mapVariations("endocrine tumour",
        "endocrine");
    
    // Malignant rhabdoid tumour
    mapVariations("malignant rhabdoid tumour",
        "malignant rhabdoid");
    
    // Pleuropulmonary blastoma
    mapVariations("pleuropulmonary blastoma");
    
    // Small cell hypercalcaemic ovarian carcinoma
    mapVariations("small cell carcinoma of ovary, hypercalcaemic type",
        "SCCOHT", "sccoht");
    
    // Phyllodes tumour
    mapVariations("phyllodes tumour",
        "phyllodes tumour of the breast");
    
    // Desmoid tumour
    mapVariations("desmoid tumour",
        "desmoid");
    
    // Congenital fibrosarcoma
    mapVariations("congenital fibrosarcoma");
    
    // Lipoblastoma
    mapVariations("lipoblastoma");
    
    // Benign tumours
    mapVariations("neurofibroma");
    mapVariations("hamartoma",
        "harmartoma", "jejunal hamartoma", "tuberous sclerosis tuber");
    mapVariations("haemangioma");
    mapVariations("lipoma");
    mapVariations("myxoma");
    mapVariations("gastrointestinal polyp",
        "gastrointestinal polyps");
    mapVariations("fibrofolliculoma",
        "fibrofolliculomas", "trichodiscomas");
    mapVariations("cylindroma");
    mapVariations("epithelioma");
    
    // Miscellaneous
    mapVariations("erythrocytosis");
    mapVariations("peritoneal carcinomatosis");
    mapVariations("pulmonary lymphangioleiomyomatosis");
    mapVariations("carcinoma");
    mapVariations("other tumours",
        "others", "other tumour types", "multiple other tumour types",
        "rare other tumour types", "various benign mesenchymal tumours");
    mapVariations("tissue type E",
        "E");
    mapVariations("tissue type M",
        "M");
    mapVariations("tissue type O",
        "O");
  }
  
  /**
   * Helper method to map multiple variations to a single standardized name.
   */
  private static void mapVariations(String standardName, String... variations) {
    // Map the standard name to itself (case-insensitive)
    CANCER_TYPE_MAP.put(standardName.toLowerCase(), standardName);
    
    // Map all variations to the standard name
    for (String variation : variations) {
      CANCER_TYPE_MAP.put(variation.toLowerCase(), standardName);
    }
  }
  
  /**
   * Map a cancer type name to its standardized display name.
   * Returns the input unchanged if no mapping is found.
   */
  public static String mapCancerType(String cancerType) {
    if (cancerType == null || cancerType.isEmpty()) {
      return cancerType;
    }
    
    String trimmed = cancerType.trim();
    String mapped = CANCER_TYPE_MAP.get(trimmed.toLowerCase());
    
    return mapped != null ? mapped : trimmed;
  }
  
  /**
   * Check if a cancer type has a standardized mapping.
   */
  public static boolean hasMapping(String cancerType) {
    if (cancerType == null || cancerType.isEmpty()) {
      return false;
    }
    return CANCER_TYPE_MAP.containsKey(cancerType.trim().toLowerCase());
  }
  
  private CancerTypeMapper() {} // Utility class
}
