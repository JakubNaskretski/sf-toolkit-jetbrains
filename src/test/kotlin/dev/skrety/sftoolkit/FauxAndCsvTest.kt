package dev.skrety.sftoolkit

import dev.skrety.sftoolkit.schema.ChildRel
import dev.skrety.sftoolkit.schema.FauxClassGenerator
import dev.skrety.sftoolkit.schema.FieldInfo
import dev.skrety.sftoolkit.schema.ObjectSchema
import dev.skrety.sftoolkit.soql.looksLikeRecordId
import dev.skrety.sftoolkit.soql.toCsv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FauxClassGeneratorTest {

    @Test
    fun `field types map to apex types, relationships get typed fields`() {
        val schema = ObjectSchema(
            name = "Account",
            fields = listOf(
                FieldInfo("Name", "string", emptyList(), null),
                FieldInfo("Industry", "picklist", emptyList(), null),
                FieldInfo("AnnualRevenue", "currency", emptyList(), null),
                FieldInfo("NumberOfEmployees", "int", emptyList(), null),
                FieldInfo("IsActive__c", "boolean", emptyList(), null),
                FieldInfo("OwnerId", "reference", listOf("User"), "Owner"),
                FieldInfo("ParentId", "reference", listOf("Account"), "Parent"),
                FieldInfo("WhatId", "reference", listOf("Account", "Opportunity"), "What"),
                FieldInfo("Mystery", "somefuturetype", emptyList(), null),
            ),
            childRelationships = listOf(ChildRel("Contacts", "Contact")),
        )
        val cls = FauxClassGenerator.generate(schema)
        assertTrue(cls.contains("global class Account {"))
        assertTrue(cls.contains("global String Name;"))
        assertTrue(cls.contains("global String Industry;"))
        assertTrue(cls.contains("global Decimal AnnualRevenue;"))
        assertTrue(cls.contains("global Integer NumberOfEmployees;"))
        assertTrue(cls.contains("global Boolean IsActive__c;"))
        // reference fields: typed relationship + raw Id field
        assertTrue(cls.contains("global User Owner;"))
        assertTrue(cls.contains("global Id OwnerId;"))
        assertTrue(cls.contains("global Account Parent;"))
        // polymorphic → SObject
        assertTrue(cls.contains("global SObject What;"))
        // unknown types default to String
        assertTrue(cls.contains("global String Mystery;"))
        assertTrue(cls.trimEnd().endsWith("}"))
    }

    @Test
    fun `custom vs standard split by double underscore`() {
        assertTrue(FauxClassGenerator.isCustom("Invoice__c"))
        assertTrue(FauxClassGenerator.isCustom("ns__Thing__c"))
        assertFalse(FauxClassGenerator.isCustom("Account"))
    }
}

class CsvTest {

    @Test
    fun `csv quotes, escapes and neutralizes formulas`() {
        val csv = toCsv(
            listOf("Id", "Name", "Note"),
            listOf(
                mapOf("Id" to "001000000000001AAA", "Name" to "Acme \"HQ\", Inc", "Note" to "=SUM(A1)"),
                mapOf("Id" to "001000000000002AAA", "Name" to "", "Note" to "+plus"),
            ),
        )
        val lines = csv.trimEnd().lines()
        assertEquals("\"Id\",\"Name\",\"Note\"", lines[0])
        assertEquals("\"001000000000001AAA\",\"Acme \"\"HQ\"\", Inc\",\"'=SUM(A1)\"", lines[1])
        assertEquals("\"001000000000002AAA\",\"\",\"'+plus\"", lines[2])
    }

    @Test
    fun `record id shape check`() {
        assertTrue(looksLikeRecordId("001gK00000AVAjtQAH"))
        assertTrue(looksLikeRecordId("001gK00000AVAjt"))
        assertFalse(looksLikeRecordId("Acme"))
        assertFalse(looksLikeRecordId("001gK00000AVAjtQAH1"))
    }
}
