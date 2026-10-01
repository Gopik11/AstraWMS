-- Goods issue documents for outbound delivery confirmations (IF-OB-003) in the simulated SAP backend.
alter table mock_sap_document drop constraint mock_sap_document_doc_type_check;
alter table mock_sap_document add constraint mock_sap_document_doc_type_check
    check (doc_type in ('GR_INBOUND_DELIVERY', 'GI_OUTBOUND_DELIVERY', 'GOODS_MOVEMENT'));
